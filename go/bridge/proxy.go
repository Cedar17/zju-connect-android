package core

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	clientpkg "github.com/mythologyli/zju-connect/client"
	"github.com/mythologyli/zju-connect/client/atrust"
	"github.com/mythologyli/zju-connect/client/atrust/auth"
	"github.com/mythologyli/zju-connect/dial"
	"github.com/mythologyli/zju-connect/resolve"
	"github.com/mythologyli/zju-connect/service"
	gvisorstack "github.com/mythologyli/zju-connect/stack/gvisor"
)

// Proxy-only mode serves the authenticated aTrust session as local SOCKS5/HTTP
// proxies bound to the loopback interface, without a VpnService TUN. The
// upstream building blocks (gVisor stack, resolver, ACL dialer, proxy
// servers) are the same ones zju-connect's desktop proxy mode uses; this file
// owns their lifecycle so the session can start and stop repeatedly inside
// one app process.

const (
	proxyPrepareTimeout  = 30 * time.Second
	proxyDNSDefaultTTL   = 3600
	proxyDNSFallback     = "10.10.0.21"
	proxySecondaryDNS    = "114.114.114.114"
	proxyListenerAddress = "127.0.0.1"
)

type proxyStartRequest struct {
	SocksPort int `json:"socksPort"`
	// HttpPort zero disables the HTTP proxy.
	HttpPort int `json:"httpPort"`
}

type proxyEvent struct {
	SchemaVersion   int    `json:"schemaVersion"`
	Type            string `json:"type"`
	State           string `json:"state"`
	Code            string `json:"code,omitempty"`
	Stage           string `json:"stage,omitempty"`
	Cause           string `json:"cause,omitempty"`
	DurationMs      int64  `json:"durationMs,omitempty"`
	SetupDurationMs int64  `json:"setupDurationMs,omitempty"`
	ReusedPrepared  bool   `json:"reusedPrepared,omitempty"`
	SocksPort       int    `json:"socksPort,omitempty"`
	HttpPort        int    `json:"httpPort,omitempty"`
	Message         string `json:"message"`
}

type preparedProxy struct {
	client              proxyClient
	stack               *gvisorstack.Stack
	resolver            *resolve.Resolver
	dialer              *dial.Dialer
	setupDurationMillis int64
}

type activeProxy struct {
	client        proxyClient
	stack         *gvisorstack.Stack
	resolver      *resolve.Resolver
	socksListener net.Listener
	httpListener  net.Listener
	socksPort     int
	httpPort      int
	listener      BridgeListener
	stopping      atomic.Bool
	fatalOnce     sync.Once
}

// proxyClient is the narrow client surface the proxy data plane needs. It
// exists so host-side tests can fake the aTrust client; production builds
// always receive *atrust.Client.
type proxyClient interface {
	clientpkg.Client
	SetupContext(ctx context.Context, options atrust.SetupOptions) ([]byte, error)
	Close()
}

var newProxyClient = func(username, sid, deviceID string) proxyClient {
	return atrust.NewClient(atrust.ClientOptions{
		Session: atrust.SessionOptions{
			Username: username,
			SID:      sid,
			DeviceID: deviceID,
		},
	})
}

var proxyState struct {
	sync.Mutex
	prepared    *preparedProxy
	active      *activeProxy
	preparation realVpnPreparationOwnership
}

func proxyErrorAt(code, stage, message string) string {
	return proxyErrorWithCause(code, stage, "", message)
}

func proxyErrorWithCause(code, stage, cause, message string) string {
	return marshal(proxyEvent{
		SchemaVersion: schemaVersion,
		Type:          "error",
		State:         "error",
		Code:          code,
		Stage:         stage,
		Cause:         cause,
		Message:       message,
	})
}

func emitProxyEvent(listener BridgeListener, event proxyEvent) {
	if listener == nil {
		return
	}
	listener.OnEvent(marshal(event))
}

// PrepareProxy consumes the reusable authenticated result held in process
// memory, runs the non-TUN aTrust client setup, and builds the local proxy
// stack. StartProxy serves it.
func PrepareProxy() string {
	proxyState.Lock()
	if proxyState.active != nil {
		proxyState.Unlock()
		return proxyErrorAt("alreadyRunning", "prepare.lifecycle", "A proxy session is already active")
	}
	if proxyState.prepared != nil {
		prepared := proxyState.prepared
		proxyState.Unlock()
		return proxyPreparedResponse(prepared, "", 0)
	}
	if proxyState.preparation.current != nil {
		proxyState.Unlock()
		return proxyErrorAt("alreadyRunning", "prepare.lifecycle", "A proxy preparation is already active")
	}
	proxyState.Unlock()

	if realVpnDataplaneBusy() {
		return proxyErrorAt("dataplaneBusy", "prepare.lifecycle", "Stop the VPN session before starting the proxy")
	}

	result, err := currentAuthenticatedVpnResult()
	if err != nil {
		return proxyErrorAt("notAuthenticated", "prepare.authentication", "Complete aTrust authentication before starting the proxy")
	}
	defer clearAuthenticatedResult(&result)

	var clientAuthData auth.ClientAuthData
	if err := json.Unmarshal(result.AuthData, &clientAuthData); err != nil || clientAuthData.DeviceID == "" {
		return proxyErrorAt("invalidAuthResult", "prepare.authentication", "The in-memory authentication result is incomplete")
	}

	// No VpnService runs in proxy mode, so plain dialing is correct and the
	// underlay stays unset on every platform.
	vpnClient := newProxyClient(result.Username, result.SID, clientAuthData.DeviceID)
	owner := newRealVpnPreparationOwner(vpnClient.Close)
	proxyState.Lock()
	if proxyState.active != nil || proxyState.prepared != nil || proxyState.preparation.current != nil {
		proxyState.Unlock()
		owner.cancel()
		return proxyErrorAt("alreadyRunning", "prepare.lifecycle", "A proxy preparation is already active")
	}
	if realVpnDataplaneBusy() {
		proxyState.Unlock()
		owner.cancel()
		return proxyErrorAt("dataplaneBusy", "prepare.lifecycle", "Stop the VPN session before starting the proxy")
	}
	if !proxyState.preparation.begin(owner) {
		proxyState.Unlock()
		owner.cancel()
		return proxyErrorAt("alreadyRunning", "prepare.lifecycle", "A proxy preparation is already active")
	}
	proxyState.Unlock()

	setupCtx, cancelSetup := context.WithTimeout(context.Background(), proxyPrepareTimeout)
	defer cancelSetup()
	setupStartedAt := time.Now()
	// Reconnect to the endpoint the session was authenticated against; the
	// experimental custom-server setting may have moved it off the ZJU default.
	if _, err := vpnClient.SetupContext(setupCtx, atrust.SetupOptions{
		ServerAddress: result.Server,
		ServerPort:    result.Port,
		ClientData:    result.AuthData,
		ResourceData:  result.ResourceData,
	}); err != nil {
		return proxySetupFailure(owner, setupCtx, err)
	}
	if err := setupCtx.Err(); err != nil {
		return proxySetupFailure(owner, setupCtx, err)
	}

	if _, err := vpnClient.IP(); err != nil {
		return proxyPreparationFailure(
			owner,
			"vpnAddressUnavailable",
			"",
			"The aTrust server did not provide a VPN address",
		)
	}

	// Mirror zju-connect's desktop proxy wiring: remote DNS through the VPN,
	// ACL dialer that refuses non-whitelisted destinations instead of letting
	// the server SHUTDOWN the whole session.
	ipResources, _ := vpnClient.IPResources()
	domainResources, _ := vpnClient.DomainResources()
	dnsResource, _ := vpnClient.DNSResource()
	useRemoteDNS := true
	remoteDNSServer, err := vpnClient.DNSServer()
	if err != nil {
		useRemoteDNS = false
		remoteDNSServer = proxyDNSFallback
	}
	secondaryDNSServer := proxySecondaryDNS
	if policyDNSServers, err := vpnClient.DNSServers(); err == nil && len(policyDNSServers) > 1 {
		secondaryDNSServer = policyDNSServers[1]
	}

	stack, err := gvisorstack.NewStack(vpnClient)
	if err != nil {
		return proxyPreparationFailure(
			owner,
			"proxyStackFailed",
			"",
			"Unable to build the local proxy network stack",
		)
	}
	resolver := resolve.NewResolver(
		stack,
		remoteDNSServer,
		secondaryDNSServer,
		proxyDNSDefaultTTL,
		domainResources,
		dnsResource,
		useRemoteDNS,
	)
	dialer := dial.NewDialer(stack, resolver, ipResources, false, "")

	prepared := &preparedProxy{
		client:              vpnClient,
		stack:               stack,
		resolver:            resolver,
		dialer:              dialer,
		setupDurationMillis: time.Since(setupStartedAt).Milliseconds(),
	}
	stage, durationMillis := owner.snapshot()
	proxyState.Lock()
	owned := proxyState.preparation.complete(owner)
	canPublish := owned && proxyState.active == nil && proxyState.prepared == nil
	if canPublish {
		proxyState.prepared = prepared
	}
	proxyState.Unlock()
	if !canPublish {
		owner.cancel()
		return proxyPreparationCancelled(stage, durationMillis)
	}

	return proxyPreparedResponse(prepared, stage, durationMillis)
}

func proxyPreparedResponse(prepared *preparedProxy, stage string, durationMillis int64) string {
	return marshal(proxyEvent{
		SchemaVersion:   schemaVersion,
		Type:            "proxyPrepared",
		SetupDurationMs: prepared.setupDurationMillis,
		ReusedPrepared:  stage == "",
		State:           "prepared",
		Stage:           stage,
		DurationMs:      durationMillis,
		Message:         "Local proxy is ready to start",
	})
}

func proxySetupFailure(owner *realVpnPreparationOwner, setupCtx context.Context, err error) string {
	stage, durationMillis := owner.snapshot()
	owned := releaseProxyPreparation(owner)
	owner.cancel()
	if !owned || errors.Is(setupCtx.Err(), context.Canceled) {
		return proxyPreparationCancelled(stage, durationMillis)
	}
	if errors.Is(setupCtx.Err(), context.DeadlineExceeded) {
		return proxyPreparationEvent(
			"error",
			"vpnPrepareTimeout",
			stage,
			"timeout",
			durationMillis,
			"Timed out while preparing the authenticated aTrust proxy",
		)
	}
	return proxyPreparationEvent(
		"error",
		"vpnSetupFailed",
		stage,
		classifyRealVpnSetupError(err),
		durationMillis,
		"Unable to prepare the authenticated aTrust proxy",
	)
}

func proxyPreparationFailure(owner *realVpnPreparationOwner, code, cause, message string) string {
	stage, durationMillis := owner.snapshot()
	owned := releaseProxyPreparation(owner)
	owner.cancel()
	if !owned {
		return proxyPreparationCancelled(stage, durationMillis)
	}
	return proxyPreparationEvent("error", code, stage, cause, durationMillis, message)
}

func proxyPreparationEvent(eventType, code, stage, cause string, durationMillis int64, message string) string {
	return marshal(proxyEvent{
		SchemaVersion: schemaVersion,
		Type:          eventType,
		State:         eventType,
		Code:          code,
		Stage:         stage,
		Cause:         cause,
		DurationMs:    durationMillis,
		Message:       message,
	})
}

func proxyPreparationCancelled(stage string, durationMillis int64) string {
	return proxyPreparationEvent("cancelled", "proxyPrepareCancelled", stage, "cancelled", durationMillis, "Proxy preparation was cancelled")
}

func releaseProxyPreparation(owner *realVpnPreparationOwner) bool {
	proxyState.Lock()
	owned := proxyState.preparation.complete(owner)
	proxyState.Unlock()
	return owned
}

// StartProxy serves the prepared proxy stack on loopback. The synchronous
// response reports the final lifecycle transition ("active" or an error);
// later events, including data-plane failures, arrive through the listener.
func StartProxy(requestJSON string, listener BridgeListener) string {
	var request proxyStartRequest
	if err := json.Unmarshal([]byte(requestJSON), &request); err != nil {
		return proxyErrorAt("invalidRequest", "start.request", "Proxy start request is not valid JSON")
	}
	if listener == nil {
		return proxyErrorAt("invalidRequest", "start.request", "Proxy listener is required")
	}
	if request.SocksPort < 1 || request.SocksPort > 65535 {
		return proxyErrorAt("invalidRequest", "start.request", "The SOCKS port must be between 1 and 65535")
	}
	if request.HttpPort < 0 || request.HttpPort > 65535 {
		return proxyErrorAt("invalidRequest", "start.request", "The HTTP port must be between 0 and 65535")
	}
	if request.HttpPort != 0 && request.HttpPort == request.SocksPort {
		return proxyErrorAt("invalidRequest", "start.request", "The SOCKS and HTTP ports must differ")
	}

	proxyState.Lock()
	prepared := proxyState.prepared
	if prepared == nil {
		proxyState.Unlock()
		return proxyErrorAt("notPrepared", "start.preparation", "Prepare the proxy before starting it")
	}
	if proxyState.active != nil || proxyState.preparation.current != nil {
		proxyState.Unlock()
		return proxyErrorAt("alreadyRunning", "start.lifecycle", "A proxy session is already active")
	}
	if realVpnDataplaneBusy() {
		proxyState.Unlock()
		return proxyErrorAt("dataplaneBusy", "start.lifecycle", "Stop the VPN session before starting the proxy")
	}
	proxyState.prepared = nil
	proxyState.Unlock()

	socksListener, err := net.Listen(
		"tcp",
		net.JoinHostPort(proxyListenerAddress, strconv.Itoa(request.SocksPort)),
	)
	if err != nil {
		prepared.client.Close()
		prepared.resolver.Close()
		return proxyErrorAt("proxyListenFailed", "start.socks", "Unable to listen on the local SOCKS port")
	}
	httpListener, err := listenOptionalHTTPProxyPort(request.HttpPort)
	if err != nil {
		_ = socksListener.Close()
		prepared.client.Close()
		prepared.resolver.Close()
		return proxyErrorAt("proxyListenFailed", "start.http", "Unable to listen on the local HTTP port")
	}

	active := &activeProxy{
		client:        prepared.client,
		stack:         prepared.stack,
		resolver:      prepared.resolver,
		socksListener: socksListener,
		httpListener:  httpListener,
		socksPort:     request.SocksPort,
		httpPort:      request.HttpPort,
		listener:      listener,
	}
	proxyState.Lock()
	if proxyState.active != nil || proxyState.prepared != nil || proxyState.preparation.current != nil {
		proxyState.Unlock()
		stopProxySession(active)
		return proxyErrorAt("alreadyRunning", "start.lifecycle", "A proxy session is already active")
	}
	proxyState.active = active
	proxyState.Unlock()

	// Fatal data-plane errors surface as events instead of crashing the app.
	prepared.stack.SetShutdownHandler(active.handleDataPlaneFailure)
	go func() {
		defer func() {
			if recovered := recover(); recovered != nil && !active.stopping.Load() {
				active.handleDataPlaneFailure(fmt.Errorf("proxy data plane panicked: %v", recovered))
			}
		}()
		active.stack.Run()
	}()

	go service.ServeSocks5OnListener(socksListener, prepared.dialer, prepared.resolver, "", "")
	if httpListener != nil {
		go service.ServeHTTPOnListener(httpListener, prepared.dialer)
	}

	return marshal(proxyEvent{
		SchemaVersion: schemaVersion,
		Type:          "proxyState",
		State:         "active",
		SocksPort:     active.socksPort,
		HttpPort:      active.httpPort,
		Message:       "The local aTrust proxy is running",
	})
}

func listenOptionalHTTPProxyPort(httpPort int) (net.Listener, error) {
	if httpPort == 0 {
		return nil, nil
	}
	return net.Listen("tcp", net.JoinHostPort(proxyListenerAddress, strconv.Itoa(httpPort)))
}

func (a *activeProxy) handleDataPlaneFailure(err error) {
	a.fatalOnce.Do(func() {
		stopProxySession(a)
		emitProxyEvent(a.listener, proxyEvent{
			SchemaVersion: schemaVersion,
			Type:          "proxyState",
			State:         "error",
			Code:          "proxyDataPlaneFailed",
			Stage:         "dataplane",
			Message:       "The aTrust proxy data plane failed: " + err.Error(),
		})
	})
}

func stopProxySession(a *activeProxy) {
	a.stopping.Store(true)
	if a.socksListener != nil {
		_ = a.socksListener.Close()
	}
	if a.httpListener != nil {
		_ = a.httpListener.Close()
	}
	if a.stack != nil {
		a.stack.Close()
	}
	if a.client != nil {
		a.client.Close()
	}
	if a.resolver != nil {
		a.resolver.Close()
	}
	proxyState.Lock()
	if proxyState.active == a {
		proxyState.active = nil
	}
	proxyState.Unlock()
}

// DiscardPreparedProxy releases a prepared, not-yet-started proxy client.
func DiscardPreparedProxy() {
	proxyState.Lock()
	prepared := proxyState.prepared
	proxyState.prepared = nil
	proxyState.Unlock()

	if prepared != nil {
		prepared.client.Close()
		prepared.resolver.Close()
	}
}

// CancelPreparingProxy releases an in-flight proxy preparation without
// changing the semantics of DiscardPreparedProxy for already prepared clients.
func CancelPreparingProxy() {
	proxyState.Lock()
	owner := proxyState.preparation.cancel()
	proxyState.Unlock()
	owner.cancel()
}

// StopProxy is idempotent and releases the active session's listeners,
// stack, L3 tunnel, and resolver. A still-prepared client is discarded too.
func StopProxy(listener BridgeListener) {
	proxyState.Lock()
	owner := proxyState.preparation.cancel()
	prepared := proxyState.prepared
	proxyState.prepared = nil
	active := proxyState.active
	proxyState.Unlock()

	owner.cancel()
	if prepared != nil {
		prepared.client.Close()
		prepared.resolver.Close()
	}

	socksPort, httpPort := 0, 0
	if active != nil {
		socksPort, httpPort = active.socksPort, active.httpPort
		stopProxySession(active)
	}

	emitProxyEvent(listener, proxyEvent{
		SchemaVersion: schemaVersion,
		Type:          "proxyState",
		State:         "stopped",
		SocksPort:     socksPort,
		HttpPort:      httpPort,
		Message:       "The local aTrust proxy is stopped",
	})
}
