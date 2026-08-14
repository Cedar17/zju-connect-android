//go:build android

package core

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/mythologyli/zju-connect/client"
	"github.com/mythologyli/zju-connect/client/atrust"
	"github.com/mythologyli/zju-connect/client/atrust/auth"
	"github.com/mythologyli/zju-connect/stack/tun"
)

const (
	realVpnMTU = int(tun.MTU)
	// l3Conn implements io.Reader by copying one complete server packet into
	// the caller's buffer. A buffer limited to the interface MTU silently
	// truncates any larger packet because that reader cannot report
	// io.ErrShortBuffer. Keep the TUN MTU at 1400, but retain a complete IPv4
	// packet here so a write failure is attributable to Android rather than a
	// locally corrupted packet.
	realVpnInboundBufferSize = 65535
)

type realVpnRoute struct {
	Address      string `json:"address"`
	PrefixLength int    `json:"prefixLength"`
}

type realVpnPreparedEvent struct {
	SchemaVersion int                         `json:"schemaVersion"`
	Type          string                      `json:"type"`
	State         string                      `json:"state"`
	Code          string                      `json:"code,omitempty"`
	Stage         string                      `json:"stage,omitempty"`
	Cause         string                      `json:"cause,omitempty"`
	Message       string                      `json:"message"`
	Address       string                      `json:"address,omitempty"`
	MTU           int                         `json:"mtu,omitempty"`
	Routes        []realVpnRoute              `json:"routes,omitempty"`
	Diagnostics   *realVpnDiagnosticsSnapshot `json:"diagnostics,omitempty"`
	Packet        *realVpnPacketMetadata      `json:"packet,omitempty"`
}

type realVpnSession struct {
	client      *atrust.Client
	tun         *os.File
	l3Conn      io.ReadWriteCloser
	listener    BridgeListener
	doneCh      chan struct{}
	stopping    atomic.Bool
	stopOnce    sync.Once
	emitMu      sync.Mutex
	diagnostics realVpnDiagnostics
}

type preparedRealVpn struct {
	client  *atrust.Client
	address string
	routes  []realVpnRoute
}

var realVpnState struct {
	sync.Mutex
	prepared *preparedRealVpn
	active   *realVpnSession
}

// PrepareRealVpn consumes the reusable authenticated result held in process
// memory. It performs the non-TUN client setup and
// returns only the Android address and routes required to establish VpnService.
// Passwords, cookies, SIDs, device IDs, sign keys, and raw resource data never
// cross this bridge event boundary.
func PrepareRealVpn() string {
	realVpnState.Lock()
	if realVpnState.active != nil {
		realVpnState.Unlock()
		return realVpnErrorAt("alreadyRunning", "prepare.lifecycle", "A real VPN session is already active")
	}
	if realVpnState.prepared != nil {
		prepared := realVpnState.prepared
		realVpnState.Unlock()
		return marshal(realVpnPreparedEvent{
			SchemaVersion: schemaVersion,
			Type:          "vpnPrepared",
			State:         "prepared",
			Message:       "Real VPN is ready for Android TUN setup",
			Address:       prepared.address,
			MTU:           realVpnMTU,
			Routes:        append([]realVpnRoute(nil), prepared.routes...),
		})
	}
	realVpnState.Unlock()

	result, err := currentInteractiveResult()
	if err != nil {
		return realVpnErrorAt("notAuthenticated", "prepare.authentication", "Complete aTrust authentication before connecting")
	}
	defer clearInteractiveResult(&result)

	var clientAuthData auth.ClientAuthData
	if err := json.Unmarshal(result.AuthData, &clientAuthData); err != nil || clientAuthData.DeviceID == "" {
		return realVpnErrorAt("invalidAuthResult", "prepare.authentication", "The in-memory authentication result is incomplete")
	}

	vpnClient := atrust.NewClient(result.Username, result.SID, clientAuthData.DeviceID, "")
	vpnClient.SetNodeTLSConfig(zjuAtrustNodeTLSConfig())
	if _, err := vpnClient.Setup(
		zjuAtrustServer,
		zjuAtrustServerPort,
		"", "", "", "", "", "", "", "", "",
		result.AuthData,
		result.ResourceData,
		0,
		"",
		false,
	); err != nil {
		vpnClient.Close()
		cause := classifyRealVpnSetupError(err)
		return realVpnErrorWithCause(
			"vpnSetupFailed",
			"prepare.setup",
			cause,
			"Unable to prepare the authenticated aTrust VPN",
		)
	}

	address, err := vpnClient.IP()
	if err != nil || address == nil || address.To4() == nil {
		vpnClient.Close()
		return realVpnErrorAt("vpnAddressUnavailable", "prepare.address", "The aTrust server did not provide a VPN address")
	}
	ipSet, err := vpnClient.IPSet()
	if err != nil || ipSet == nil {
		vpnClient.Close()
		return realVpnErrorAt("vpnRoutesUnavailable", "prepare.routes", "The aTrust server did not provide VPN routes")
	}

	routes := make([]realVpnRoute, 0)
	for _, prefix := range ipSet.Prefixes() {
		if !prefix.IP().Is4() {
			continue
		}
		routes = append(routes, realVpnRoute{
			Address:      prefix.IP().String(),
			PrefixLength: int(prefix.Bits()),
		})
	}
	if len(routes) == 0 {
		vpnClient.Close()
		return realVpnErrorAt("vpnRoutesUnavailable", "prepare.routes", "The aTrust server returned no IPv4 VPN routes")
	}

	prepared := &preparedRealVpn{
		client:  vpnClient,
		address: address.To4().String(),
		routes:  routes,
	}
	realVpnState.Lock()
	if realVpnState.active != nil || realVpnState.prepared != nil {
		realVpnState.Unlock()
		vpnClient.Close()
		return realVpnErrorAt("alreadyRunning", "prepare.lifecycle", "A real VPN session is already active")
	}
	realVpnState.prepared = prepared
	realVpnState.Unlock()

	return marshal(realVpnPreparedEvent{
		SchemaVersion: schemaVersion,
		Type:          "vpnPrepared",
		State:         "prepared",
		Message:       "Real VPN is ready for Android TUN setup",
		Address:       prepared.address,
		MTU:           realVpnMTU,
		Routes:        append([]realVpnRoute(nil), prepared.routes...),
	})
}

// StartRealVpn attaches an Android TUN to the prepared authenticated client.
// The caller transfers ownership of tunFD to this bridge until StopRealVpn.
func StartRealVpn(tunFD int, protector SocketProtector, listener BridgeListener) {
	if tunFD < 0 {
		emitRealVpnState(listener, "error", "invalidTunFd", "attach.tun", "The VPN file descriptor is invalid", nil)
		return
	}
	if protector == nil {
		emitRealVpnState(listener, "error", "missingSocketProtector", "attach.protection", "Android socket protection is required", nil)
		return
	}

	realVpnState.Lock()
	prepared := realVpnState.prepared
	if prepared == nil {
		realVpnState.Unlock()
		emitRealVpnState(listener, "error", "notPrepared", "attach.preparation", "Prepare the real VPN before attaching its TUN", nil)
		return
	}
	if realVpnState.active != nil {
		realVpnState.Unlock()
		emitRealVpnState(listener, "error", "alreadyRunning", "attach.lifecycle", "A real VPN session is already active", nil)
		return
	}
	realVpnState.prepared = nil
	realVpnState.Unlock()

	prepared.client.SetSocketProtector(protector)
	if err := prepareTunFileDescriptor(tunFD); err != nil {
		prepared.client.Close()
		closeTunFile(os.NewFile(uintptr(tunFD), "zju-connect-real-tun-invalid"))
		emitRealVpnState(listener, "error", "tunInitializationFailed", "attach.tun", "Unable to prepare the Android VPN interface", nil)
		return
	}
	tunFile := os.NewFile(uintptr(tunFD), "zju-connect-real-tun")
	if tunFile == nil {
		prepared.client.Close()
		emitRealVpnState(listener, "error", "tunInitializationFailed", "attach.tun", "Unable to open the Android VPN interface", nil)
		return
	}
	session := &realVpnSession{
		client:   prepared.client,
		tun:      tunFile,
		listener: listener,
		doneCh:   make(chan struct{}),
	}

	realVpnState.Lock()
	if realVpnState.active != nil {
		realVpnState.Unlock()
		prepared.client.Close()
		closeTunFile(tunFile)
		emitRealVpnState(listener, "error", "alreadyRunning", "attach.lifecycle", "A real VPN session is already active", nil)
		return
	}
	realVpnState.active = session
	realVpnState.Unlock()

	emitRealVpnState(listener, "starting", "", "dataplane.start", "Starting the real aTrust VPN data plane", session)
	go session.run()
}

// DiscardPreparedRealVpn releases only a prepared, not-yet-attached client.
// It intentionally leaves an active VPN session untouched.
func DiscardPreparedRealVpn() {
	realVpnState.Lock()
	prepared := realVpnState.prepared
	realVpnState.prepared = nil
	realVpnState.Unlock()

	if prepared != nil {
		prepared.client.Close()
	}
}

// StopRealVpn is idempotent and releases the prepared client, TUN, underlay
// sockets, and L3 reader goroutines.
func StopRealVpn() {
	realVpnState.Lock()
	prepared := realVpnState.prepared
	realVpnState.prepared = nil
	session := realVpnState.active
	realVpnState.Unlock()

	if prepared != nil {
		prepared.client.Close()
	}
	if session == nil {
		return
	}

	session.stopOnce.Do(func() {
		session.stopping.Store(true)
		emitRealVpnState(session.listener, "stopping", "", "lifecycle.stop", "Stopping the real aTrust VPN data plane", session)
		session.client.Close()
		closeTunFile(session.tun)
	})
	select {
	case <-session.doneCh:
	case <-time.After(2 * time.Second):
		emitRealVpnState(session.listener, "error", "stopTimeout", "lifecycle.stop", "Timed out waiting for VPN cleanup", session)
	}
}

func (s *realVpnSession) run() {
	defer close(s.doneCh)
	failure := safeRunRealVpnStack(s)
	if s.l3Conn != nil {
		_ = s.l3Conn.Close()
	}
	closeTunFile(s.tun)
	if s.stopping.Load() {
		emitRealVpnState(s.listener, "stopped", "", "lifecycle.stop", "Real aTrust VPN stopped", s)
		clearActiveRealVpn(s)
		return
	}
	s.client.Close()
	if failure == nil {
		failure = &realVpnFailure{
			code:    "vpnDataPlaneStopped",
			stage:   "dataplane.runtime",
			message: "The real aTrust VPN data plane stopped unexpectedly",
		}
	}
	emitRealVpnFailure(s.listener, failure, s)
	clearActiveRealVpn(s)
}

type realVpnFailure struct {
	code    string
	stage   string
	cause   string
	message string
}

func safeRunRealVpnStack(session *realVpnSession) (failure *realVpnFailure) {
	defer func() {
		if recovered := recover(); recovered != nil {
			failure = &realVpnFailure{
				code:    "vpnDataPlanePanic",
				stage:   "dataplane.runtime",
				message: "The real aTrust VPN data plane failed",
			}
		}
	}()
	emitRealVpnState(session.listener, "active", "", "dataplane.active", "Real aTrust VPN is active", session)
	return runRealVpnStack(session)
}

// runRealVpnStack is the Android TUN loop. Resource misses and packets rejected
// while the L3 transport recovers are dropped without ending the Android VPN.
func runRealVpnStack(session *realVpnSession) *realVpnFailure {
	l3Conn, err := session.client.NewL3Conn()
	if err != nil {
		return &realVpnFailure{
			code:    "l3ConnectionInitFailed",
			stage:   "dataplane.l3.init",
			message: "Unable to initialize the aTrust data connection",
		}
	}
	session.l3Conn = l3Conn

	failureCh := make(chan *realVpnFailure, 1)
	var failureOnce sync.Once
	reportFailure := func(failure *realVpnFailure) {
		failureOnce.Do(func() { failureCh <- failure })
	}
	if eventSource, ok := l3Conn.(interface {
		Events() <-chan atrust.L3TunnelEvent
	}); ok {
		go func() {
			for event := range eventSource.Events() {
				switch event.State {
				case atrust.L3TunnelStateRecovering:
					emitRealVpnState(
						session.listener,
						"recovering",
						"l3Reconnecting",
						"dataplane.l3.reconnect",
						"The aTrust data connection is recovering",
						session,
					)
				case atrust.L3TunnelStateActive:
					emitRealVpnState(
						session.listener,
						"active",
						"",
						"dataplane.l3.recovered",
						"The aTrust data connection recovered",
						session,
					)
				case atrust.L3TunnelStateFailed:
					reportFailure(realVpnFailureFromL3Event(event))
				}
			}
		}()
	}
	go func() {
		buf := make([]byte, realVpnMTU)
		for {
			n, readErr := session.tun.Read(buf)
			if readErr != nil {
				reportFailure(&realVpnFailure{code: "vpnTunReadFailed", stage: "dataplane.tun.read", message: "Unable to read the Android VPN interface"})
				return
			}
			if n == 0 {
				continue
			}
			session.diagnostics.tunReadPackets.Add(1)
			session.diagnostics.tunReadBytes.Add(uint64(n))
			packet := buf[:n]
			if !isForwardableRealVpnPacket(packet) {
				session.diagnostics.filteredPackets.Add(1)
				emitRealVpnObservation(session, "dataplane.tun.read", "filteredPacket", packet)
				continue
			}
			session.diagnostics.forwardablePackets.Add(1)
			emitRealVpnObservation(session, "dataplane.tun.read", "", packet)

			session.diagnostics.l3WriteAttempts.Add(1)
			if _, writeErr := l3Conn.Write(packet); writeErr != nil {
				if errors.Is(writeErr, atrust.ErrL3TunnelRecovering) {
					emitRealVpnObservation(session, "dataplane.l3.write", "l3Recovering", packet)
					continue
				}
				if errors.Is(writeErr, client.ErrResourceNotFound) {
					session.diagnostics.resourceDrops.Add(1)
					emitRealVpnObservation(session, "dataplane.l3.write", "resourceNotFound", packet)
					continue
				}
				reportFailure(&realVpnFailure{
					code:    "vpnPacketForwardFailed",
					stage:   "dataplane.l3.write",
					cause:   classifyL3WriteError(writeErr),
					message: "The aTrust data connection rejected a VPN packet",
				})
				return
			}
			session.diagnostics.l3WriteSuccesses.Add(1)
			emitRealVpnObservation(session, "dataplane.l3.write", "", packet)
		}
	}()

	go func() {
		buf := make([]byte, realVpnInboundBufferSize)
		for {
			n, readErr := l3Conn.Read(buf)
			if readErr != nil {
				reportFailure(&realVpnFailure{code: "vpnServerReadFailed", stage: "dataplane.l3.read", message: "The aTrust data connection closed unexpectedly"})
				return
			}
			if n == 0 {
				continue
			}
			session.diagnostics.l3ReadPackets.Add(1)
			session.diagnostics.l3ReadBytes.Add(uint64(n))
			packet := buf[:n]
			meta := inspectRealVpnPacket("dataplane.l3.read", packet)
			readCause := ""
			if !meta.Valid {
				session.diagnostics.l3InvalidPackets.Add(1)
				if meta.Truncated {
					readCause = "truncatedPacket"
				} else {
					readCause = "invalidPacket"
				}
			}
			emitRealVpnObservation(session, "dataplane.l3.read", readCause, packet)

			session.diagnostics.tunWriteAttempts.Add(1)
			if _, writeErr := session.tun.Write(packet); writeErr != nil {
				reportFailure(&realVpnFailure{
					code:    "vpnTunWriteFailed",
					stage:   "dataplane.tun.write",
					cause:   classifyTunWriteError(writeErr),
					message: "Unable to write aTrust data to the Android VPN interface",
				})
				return
			}
			session.diagnostics.tunWriteSuccesses.Add(1)
			session.diagnostics.tunWriteBytes.Add(uint64(n))
			emitRealVpnObservation(session, "dataplane.tun.write", "", packet)
		}
	}()

	return <-failureCh
}

func realVpnFailureFromL3Event(event atrust.L3TunnelEvent) *realVpnFailure {
	switch event.Failure {
	case atrust.L3TunnelFailureAuthentication:
		return &realVpnFailure{
			code:    "vpnSessionInvalid",
			stage:   "dataplane.l3.reconnect",
			cause:   "authentication",
			message: "The authenticated VPN session is no longer valid",
		}
	default:
		return &realVpnFailure{
			code:    "vpnConfigurationUnavailable",
			stage:   "dataplane.l3.reconnect",
			cause:   "configuration",
			message: "The aTrust data connection configuration is unavailable",
		}
	}
}

func clearActiveRealVpn(session *realVpnSession) {
	realVpnState.Lock()
	if realVpnState.active == session {
		realVpnState.active = nil
	}
	realVpnState.Unlock()
}

func closeTunFile(tunFile *os.File) {
	if tunFile != nil {
		_ = tunFile.Close()
	}
}

func currentInteractiveResult() (auth.InteractiveResult, error) {
	result, ok := currentAuthenticatedResult()
	if !ok || result.SID == "" || len(result.AuthData) == 0 || len(result.ResourceData) == 0 {
		return auth.InteractiveResult{}, fmt.Errorf("authentication has not completed")
	}
	return result, nil
}

func emitRealVpnState(listener BridgeListener, state, code, stage, message string, session *realVpnSession) {
	if listener == nil {
		return
	}
	event := realVpnPreparedEvent{
		SchemaVersion: schemaVersion,
		Type:          "vpnState",
		State:         state,
		Code:          code,
		Stage:         stage,
		Message:       message,
	}
	if session != nil {
		event.MTU = realVpnMTU
		snapshot := session.diagnostics.snapshot()
		event.Diagnostics = &snapshot
	}
	emitRealVpnEvent(listener, session, event)
}

func emitRealVpnObservation(session *realVpnSession, stage, cause string, packet []byte) {
	if session == nil || session.listener == nil {
		return
	}
	meta := session.diagnostics.samplePacket(stage, packet)
	if meta == nil {
		return
	}
	snapshot := session.diagnostics.snapshot()
	emitRealVpnEvent(session.listener, session, realVpnPreparedEvent{
		SchemaVersion: schemaVersion,
		Type:          "vpnDiagnostic",
		State:         "diagnostic",
		Stage:         stage,
		Cause:         cause,
		Message:       "Real VPN data-plane observation",
		MTU:           realVpnMTU,
		Diagnostics:   &snapshot,
		Packet:        meta,
	})
}

func emitRealVpnFailure(listener BridgeListener, failure *realVpnFailure, session *realVpnSession) {
	if listener == nil || failure == nil {
		return
	}
	event := realVpnPreparedEvent{
		SchemaVersion: schemaVersion,
		Type:          "vpnState",
		State:         "error",
		Code:          failure.code,
		Stage:         failure.stage,
		Cause:         failure.cause,
		Message:       failure.message,
	}
	if session != nil {
		event.MTU = realVpnMTU
		snapshot := session.diagnostics.snapshot()
		event.Diagnostics = &snapshot
	}
	emitRealVpnEvent(listener, session, event)
}

func emitRealVpnEvent(listener BridgeListener, session *realVpnSession, event realVpnPreparedEvent) {
	if listener == nil {
		return
	}
	if session != nil {
		session.emitMu.Lock()
		defer session.emitMu.Unlock()
	}
	listener.OnEvent(marshal(event))
}

func realVpnError(code, message string) string {
	return realVpnErrorAt(code, "prepare", message)
}

func realVpnErrorAt(code, stage, message string) string {
	return realVpnErrorWithCause(code, stage, "", message)
}

// classifyRealVpnSetupError produces a stable, non-sensitive log category.
// Do not pass err.Error() across the mobile callback boundary: upstream errors
// can contain server responses that are not safe to put in UI or logcat.
func classifyRealVpnSetupError(err error) string {
	if err == nil {
		return "unknown"
	}
	errText := strings.ToLower(err.Error())
	switch {
	case strings.Contains(errText, "timeout") || strings.Contains(errText, "deadline exceeded"):
		return "timeout"
	case strings.Contains(errText, "no route to host") ||
		strings.Contains(errText, "network is unreachable") ||
		strings.Contains(errText, "connection refused") ||
		strings.Contains(errText, "connection reset") ||
		strings.Contains(errText, "network unavailable"):
		return "networkUnavailable"
	case strings.Contains(errText, "permission denied") || strings.Contains(errText, "operation not permitted"):
		return "permissionDenied"
	case strings.Contains(errText, "x509") || strings.Contains(errText, "tls") || strings.Contains(errText, "certificate"):
		return "tlsValidation"
	case strings.Contains(errText, "no reachable node"):
		return "noReachableNode"
	case strings.Contains(errText, "failed to connect to the server"):
		return "serverRejected"
	case strings.Contains(errText, "unexpected response"):
		return "protocolRejected"
	default:
		return "unexpected"
	}
}

func realVpnErrorWithCause(code, stage, cause, message string) string {
	return marshal(realVpnPreparedEvent{
		SchemaVersion: schemaVersion,
		Type:          "error",
		State:         "error",
		Code:          code,
		Stage:         stage,
		Cause:         cause,
		Message:       message,
	})
}
