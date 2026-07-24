//go:build android

package core

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"github.com/mythologyli/zju-connect/client"
	"github.com/mythologyli/zju-connect/client/atrust"
	"github.com/mythologyli/zju-connect/client/atrust/auth"
	"github.com/mythologyli/zju-connect/stack/tun"
)

const realVpnMTU = int(tun.MTU)

type realVpnRoute struct {
	Address      string `json:"address"`
	PrefixLength int    `json:"prefixLength"`
}

type realVpnPreparedEvent struct {
	SchemaVersion int            `json:"schemaVersion"`
	Type          string         `json:"type"`
	State         string         `json:"state"`
	Code          string         `json:"code,omitempty"`
	Message       string         `json:"message"`
	Address       string         `json:"address,omitempty"`
	MTU           int            `json:"mtu,omitempty"`
	Routes        []realVpnRoute `json:"routes,omitempty"`
}

type realVpnSession struct {
	client   *atrust.Client
	tun      *os.File
	l3Conn   io.ReadWriteCloser
	listener BridgeListener
	doneCh   chan struct{}
	stopping atomic.Bool
	stopOnce sync.Once
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

// PrepareRealVpn consumes the authenticated result held by the current
// in-memory authentication flow. It performs the non-TUN client setup and
// returns only the Android address and routes required to establish VpnService.
// Passwords, cookies, SIDs, device IDs, sign keys, and raw resource data never
// cross this bridge event boundary.
func PrepareRealVpn() string {
	realVpnState.Lock()
	if realVpnState.active != nil {
		realVpnState.Unlock()
		return realVpnError("alreadyRunning", "A real VPN session is already active")
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
		return realVpnError("notAuthenticated", "Complete aTrust authentication before connecting")
	}

	var clientAuthData auth.ClientAuthData
	if err := json.Unmarshal(result.AuthData, &clientAuthData); err != nil || clientAuthData.DeviceID == "" {
		return realVpnError("invalidAuthResult", "The in-memory authentication result is incomplete")
	}

	vpnClient := atrust.NewClient(result.Username, result.SID, clientAuthData.DeviceID, "")
	if _, err := vpnClient.Setup(
		zjuAtrustServer,
		zjuAtrustServerPort,
		"", "", "", "", "", "", "", "",
		result.AuthData,
		result.ResourceData,
		0,
		"",
		true,
	); err != nil {
		vpnClient.Close()
		return realVpnError("vpnSetupFailed", "Unable to prepare the authenticated aTrust VPN")
	}

	address, err := vpnClient.IP()
	if err != nil || address == nil || address.To4() == nil {
		vpnClient.Close()
		return realVpnError("vpnAddressUnavailable", "The aTrust server did not provide a VPN address")
	}
	ipSet, err := vpnClient.IPSet()
	if err != nil || ipSet == nil {
		vpnClient.Close()
		return realVpnError("vpnRoutesUnavailable", "The aTrust server did not provide VPN routes")
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
		return realVpnError("vpnRoutesUnavailable", "The aTrust server returned no IPv4 VPN routes")
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
		return realVpnError("alreadyRunning", "A real VPN session is already active")
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
		emitRealVpnState(listener, "error", "invalidTunFd", "The VPN file descriptor is invalid", nil)
		return
	}
	if protector == nil {
		emitRealVpnState(listener, "error", "missingSocketProtector", "Android socket protection is required", nil)
		return
	}

	realVpnState.Lock()
	prepared := realVpnState.prepared
	if prepared == nil {
		realVpnState.Unlock()
		emitRealVpnState(listener, "error", "notPrepared", "Prepare the real VPN before attaching its TUN", nil)
		return
	}
	if realVpnState.active != nil {
		realVpnState.Unlock()
		emitRealVpnState(listener, "error", "alreadyRunning", "A real VPN session is already active", nil)
		return
	}
	realVpnState.prepared = nil
	realVpnState.Unlock()

	prepared.client.SetSocketProtector(protector)
	tunFile := os.NewFile(uintptr(tunFD), "zju-connect-real-tun")
	if tunFile == nil {
		prepared.client.Close()
		emitRealVpnState(listener, "error", "tunInitializationFailed", "Unable to open the Android VPN interface", nil)
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
		emitRealVpnState(listener, "error", "alreadyRunning", "A real VPN session is already active", nil)
		return
	}
	realVpnState.active = session
	realVpnState.Unlock()

	emitRealVpnState(listener, "starting", "", "Starting the real aTrust VPN data plane", session)
	go session.run()
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
		emitRealVpnState(session.listener, "stopping", "", "Stopping the real aTrust VPN data plane", session)
		session.client.Close()
		closeTunFile(session.tun)
	})
	select {
	case <-session.doneCh:
	case <-time.After(2 * time.Second):
		emitRealVpnState(session.listener, "error", "stopTimeout", "Timed out waiting for VPN cleanup", session)
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
		emitRealVpnState(s.listener, "stopped", "", "Real aTrust VPN stopped", s)
		clearActiveRealVpn(s)
		return
	}
	s.client.Close()
	if failure == nil {
		failure = &realVpnFailure{
			code:    "vpnDataPlaneStopped",
			message: "The real aTrust VPN data plane stopped unexpectedly",
		}
	}
	emitRealVpnState(s.listener, "error", failure.code, failure.message, s)
	clearActiveRealVpn(s)
}

type realVpnFailure struct {
	code    string
	message string
}

func safeRunRealVpnStack(session *realVpnSession) (failure *realVpnFailure) {
	defer func() {
		if recovered := recover(); recovered != nil {
			failure = &realVpnFailure{
				code:    "vpnDataPlanePanic",
				message: "The real aTrust VPN data plane failed",
			}
		}
	}()
	emitRealVpnState(session.listener, "active", "", "Real aTrust VPN is active", session)
	return runRealVpnStack(session)
}

// runRealVpnStack is the Android TUN loop. aTrust's L3 writer returns
// ErrResourceNotFound for packets outside the server-advertised resources;
// those packets must be dropped, not treated as a fatal VPN failure.
func runRealVpnStack(session *realVpnSession) *realVpnFailure {
	l3Conn, err := session.client.NewL3Conn()
	if err != nil {
		return &realVpnFailure{
			code:    "l3ConnectionInitFailed",
			message: "Unable to initialize the aTrust data connection",
		}
	}
	session.l3Conn = l3Conn

	failureCh := make(chan *realVpnFailure, 2)
	go func() {
		buf := make([]byte, realVpnMTU)
		for {
			n, readErr := session.tun.Read(buf)
			if readErr != nil {
				failureCh <- &realVpnFailure{code: "vpnTunReadFailed", message: "Unable to read the Android VPN interface"}
				return
			}
			if n == 0 {
				continue
			}
			if !isForwardableIPv4Packet(buf[:n]) {
				continue
			}
			if _, writeErr := l3Conn.Write(buf[:n]); writeErr != nil {
				if errors.Is(writeErr, client.ErrResourceNotFound) {
					continue
				}
				failureCh <- &realVpnFailure{code: "vpnPacketForwardFailed", message: "The aTrust data connection rejected a VPN packet"}
				return
			}
		}
	}()

	go func() {
		buf := make([]byte, realVpnMTU)
		for {
			n, readErr := l3Conn.Read(buf)
			if readErr != nil {
				failureCh <- &realVpnFailure{code: "vpnServerReadFailed", message: "The aTrust data connection closed unexpectedly"}
				return
			}
			if n == 0 {
				continue
			}
			if _, writeErr := session.tun.Write(buf[:n]); writeErr != nil {
				failureCh <- &realVpnFailure{code: "vpnTunWriteFailed", message: "Unable to write aTrust data to the Android VPN interface"}
				return
			}
		}
	}()

	return <-failureCh
}

func isForwardableIPv4Packet(packet []byte) bool {
	if len(packet) < 20 || packet[0]>>4 != 4 {
		return false
	}
	headerLength := int(packet[0]&0x0f) * 4
	if headerLength < 20 || len(packet) < headerLength {
		return false
	}
	return packet[9] == 6 || packet[9] == 17
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
	currentAuthentication.mu.Lock()
	flow := currentAuthentication.flow
	currentAuthentication.mu.Unlock()
	if flow == nil {
		return auth.InteractiveResult{}, fmt.Errorf("authentication is not available")
	}
	result, ok := flow.Result()
	if !ok || result.SID == "" || len(result.AuthData) == 0 || len(result.ResourceData) == 0 {
		return auth.InteractiveResult{}, fmt.Errorf("authentication has not completed")
	}
	return result, nil
}

func emitRealVpnState(listener BridgeListener, state, code, message string, session *realVpnSession) {
	if listener == nil {
		return
	}
	event := realVpnPreparedEvent{
		SchemaVersion: schemaVersion,
		Type:          "vpnState",
		State:         state,
		Code:          code,
		Message:       message,
	}
	if session != nil {
		event.MTU = realVpnMTU
	}
	listener.OnEvent(marshal(event))
}

func realVpnError(code, message string) string {
	return marshal(realVpnPreparedEvent{
		SchemaVersion: schemaVersion,
		Type:          "error",
		State:         "error",
		Code:          code,
		Message:       message,
	})
}
