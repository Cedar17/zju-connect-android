package core

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"os"
	"sync"
	"syscall"
	"time"
)

const (
	testDataPlaneMTU = 1400
	testMarker       = "zju-connect-tun-test-v1"
	testStateType    = "testVpnState"
)

// SocketProtector is implemented by Android's VpnService. Go invokes it from
// net.Dialer.Control after the operating system creates the socket and before
// the socket connects to the fake transport.
type SocketProtector interface {
	Protect(socketFD int) bool
}

// StartTestDataPlane starts the credential-free Issue #6 data-plane probe.
// tunFD ownership moves to Go; StopTestDataPlane closes it exactly once.
func StartTestDataPlane(tunFD int, protector SocketProtector, listener BridgeListener) {
	if tunFD < 0 {
		emitTestState(listener, "error", "invalidTunFd", "TUN file descriptor is invalid", nil)
		return
	}
	if protector == nil {
		emitTestState(listener, "error", "missingSocketProtector", "Socket protector is required", nil)
		return
	}

	testDataPlaneMu.Lock()
	if activeTestDataPlane != nil {
		testDataPlaneMu.Unlock()
		emitTestState(listener, "error", "alreadyRunning", "Test data plane is already running", nil)
		return
	}

	session := &testDataPlaneSession{
		tunFD:     tunFD,
		protector: protector,
		listener:  listener,
		stopCh:    make(chan struct{}),
		doneCh:    make(chan struct{}),
	}
	activeTestDataPlane = session
	testDataPlaneMu.Unlock()

	emitTestState(listener, "starting", "", "Starting synthetic TUN data plane", session)
	if err := session.openTransport(); err != nil {
		session.closeResources()
		clearActiveTestDataPlane(session)
		emitTestState(listener, "error", "transportUnavailable", "Unable to start synthetic transport", session)
		return
	}

	emitTestState(listener, "tunAttached", "", "Go owns the TUN file descriptor", session)
	emitTestState(listener, "socketProtected", "", "Synthetic transport socket protected", session)
	go session.run()
}

// StopTestDataPlane is idempotent and safe to call when no test session exists.
func StopTestDataPlane() {
	testDataPlaneMu.Lock()
	session := activeTestDataPlane
	testDataPlaneMu.Unlock()
	if session == nil {
		return
	}

	emitTestState(session.listener, "stopping", "", "Stopping synthetic TUN data plane", session)
	session.stop()
	clearActiveTestDataPlane(session)
	emitTestState(session.listener, "stopped", "", "Synthetic TUN data plane stopped", session)
}

type testDataPlaneSession struct {
	tunFD     int
	protector SocketProtector
	listener  BridgeListener

	tun       *os.File
	echoConn  *net.UDPConn
	transport *net.UDPConn

	stopOnce      sync.Once
	resourcesMu   sync.Mutex
	resourcesDone bool
	stopCh        chan struct{}
	doneCh        chan struct{}

	statsMu         sync.Mutex
	packetsFromTun  int64
	packetsToTun    int64
	bytesFromTun    int64
	bytesToTun      int64
	markerReflected bool
}

func (s *testDataPlaneSession) openTransport() error {
	tun := os.NewFile(uintptr(s.tunFD), "zju-connect-test-tun")
	if tun == nil {
		return errors.New("unable to wrap TUN file descriptor")
	}
	s.tun = tun

	echoConn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		return err
	}
	s.echoConn = echoConn
	go runEchoServer(echoConn)

	var protectErr error
	dialer := &net.Dialer{
		Control: func(_ string, _ string, rawConn syscall.RawConn) error {
			return rawConn.Control(func(fd uintptr) {
				if !s.protector.Protect(int(fd)) {
					protectErr = errors.New("VpnService.protect returned false")
				}
			})
		},
	}

	conn, err := dialer.DialContext(context.Background(), "udp4", echoConn.LocalAddr().String())
	if err != nil {
		return err
	}
	if protectErr != nil {
		_ = conn.Close()
		return protectErr
	}

	transport, ok := conn.(*net.UDPConn)
	if !ok {
		_ = conn.Close()
		return errors.New("synthetic transport is not a UDP connection")
	}
	s.transport = transport
	return nil
}

func runEchoServer(conn *net.UDPConn) {
	buffer := make([]byte, testDataPlaneMTU)
	for {
		n, address, err := conn.ReadFromUDP(buffer)
		if err != nil {
			return
		}
		if _, err := conn.WriteToUDP(buffer[:n], address); err != nil {
			return
		}
	}
}

func (s *testDataPlaneSession) run() {
	defer close(s.doneCh)
	defer s.closeResources()

	buffer := make([]byte, testDataPlaneMTU)
	for {
		n, err := s.tun.Read(buffer)
		if err != nil {
			if !s.isStopping() {
				emitTestState(s.listener, "error", "tunReadFailed", "Unable to read from TUN", s)
			}
			return
		}
		if n == 0 {
			continue
		}

		packet := append([]byte(nil), buffer[:n]...)
		response, err := s.roundTrip(packet)
		if err != nil {
			if !errors.Is(err, errIgnoredTestPacket) && !s.isStopping() {
				emitTestState(s.listener, "error", "packetFailed", "Synthetic packet round trip failed", s)
			}
			continue
		}

		if _, err := s.tun.Write(response); err != nil {
			if !s.isStopping() {
				emitTestState(s.listener, "error", "tunWriteFailed", "Unable to write response to TUN", s)
			}
			return
		}
		s.statsMu.Lock()
		s.packetsToTun++
		s.bytesToTun += int64(len(response))
		wasReflected := s.markerReflected
		s.statsMu.Unlock()
		if !wasReflected {
			emitTestState(s.listener, "roundTripVerified", "", "Synthetic marker packet reflected through Go", s)
		}
		s.statsMu.Lock()
		s.markerReflected = true
		s.statsMu.Unlock()
	}
}

var errIgnoredTestPacket = errors.New("test packet ignored")

func (s *testDataPlaneSession) roundTrip(packet []byte) ([]byte, error) {
	if !isMarkedUDPv4Packet(packet) {
		return nil, errIgnoredTestPacket
	}

	s.statsMu.Lock()
	s.packetsFromTun++
	s.bytesFromTun += int64(len(packet))
	s.statsMu.Unlock()

	if _, err := s.transport.Write(packet); err != nil {
		return nil, err
	}
	response := make([]byte, testDataPlaneMTU)
	_ = s.transport.SetReadDeadline(time.Now().Add(3 * time.Second))
	n, err := s.transport.Read(response)
	if err != nil {
		return nil, err
	}
	return reflectUDPv4Packet(response[:n])
}

func (s *testDataPlaneSession) stop() {
	s.stopOnce.Do(func() {
		close(s.stopCh)
		s.closeResources()
	})
	select {
	case <-s.doneCh:
	case <-time.After(2 * time.Second):
		emitTestState(s.listener, "error", "stopTimeout", "Timed out waiting for data plane shutdown", s)
	}
}

func (s *testDataPlaneSession) closeResources() {
	s.resourcesMu.Lock()
	if s.resourcesDone {
		s.resourcesMu.Unlock()
		return
	}
	s.resourcesDone = true
	tun := s.tun
	transport := s.transport
	echoConn := s.echoConn
	s.resourcesMu.Unlock()

	if tun != nil {
		_ = tun.Close()
	}
	if transport != nil {
		_ = transport.Close()
	}
	if echoConn != nil {
		_ = echoConn.Close()
	}
}

func (s *testDataPlaneSession) isStopping() bool {
	select {
	case <-s.stopCh:
		return true
	default:
		return false
	}
}

func clearActiveTestDataPlane(session *testDataPlaneSession) {
	testDataPlaneMu.Lock()
	if activeTestDataPlane == session {
		activeTestDataPlane = nil
	}
	testDataPlaneMu.Unlock()
}

var testDataPlaneMu sync.Mutex
var activeTestDataPlane *testDataPlaneSession

type testStatePayload struct {
	SchemaVersion  int    `json:"schemaVersion"`
	Type           string `json:"type"`
	State          string `json:"state"`
	Code           string `json:"code,omitempty"`
	Message        string `json:"message"`
	PacketsFromTun int64  `json:"packetsFromTun"`
	PacketsToTun   int64  `json:"packetsToTun"`
	BytesFromTun   int64  `json:"bytesFromTun"`
	BytesToTun     int64  `json:"bytesToTun"`
}

func emitTestState(listener BridgeListener, state, code, message string, session *testDataPlaneSession) {
	if listener == nil {
		return
	}
	payload := testStatePayload{
		SchemaVersion: schemaVersion,
		Type:          testStateType,
		State:         state,
		Code:          code,
		Message:       message,
	}
	if session != nil {
		session.statsMu.Lock()
		payload.PacketsFromTun = session.packetsFromTun
		payload.PacketsToTun = session.packetsToTun
		payload.BytesFromTun = session.bytesFromTun
		payload.BytesToTun = session.bytesToTun
		session.statsMu.Unlock()
	}
	listener.OnEvent(marshal(payload))
}

func isMarkedUDPv4Packet(packet []byte) bool {
	if len(packet) < 20 || packet[0]>>4 != 4 {
		return false
	}
	headerLength := int(packet[0]&0x0f) * 4
	if headerLength < 20 || len(packet) < headerLength+8 || packet[9] != 17 {
		return false
	}
	udpLength := int(binary.BigEndian.Uint16(packet[headerLength+4 : headerLength+6]))
	if udpLength < 8 || len(packet) < headerLength+udpLength {
		return false
	}
	return bytes.Contains(packet[headerLength+8:headerLength+udpLength], []byte(testMarker))
}

func reflectUDPv4Packet(packet []byte) ([]byte, error) {
	if len(packet) < 20 || packet[0]>>4 != 4 {
		return nil, fmt.Errorf("invalid IPv4 packet")
	}
	headerLength := int(packet[0]&0x0f) * 4
	if headerLength < 20 || len(packet) < headerLength+8 || packet[9] != 17 {
		return nil, fmt.Errorf("packet is not IPv4 UDP")
	}
	totalLength := int(binary.BigEndian.Uint16(packet[2:4]))
	if totalLength < headerLength+8 || totalLength > len(packet) {
		return nil, fmt.Errorf("invalid IPv4 total length")
	}
	udpLength := int(binary.BigEndian.Uint16(packet[headerLength+4 : headerLength+6]))
	if udpLength < 8 || headerLength+udpLength > totalLength {
		return nil, fmt.Errorf("invalid UDP length")
	}

	response := append([]byte(nil), packet[:totalLength]...)
	for i := 0; i < 4; i++ {
		response[12+i], response[16+i] = response[16+i], response[12+i]
	}
	response[headerLength+0], response[headerLength+2] = response[headerLength+2], response[headerLength+0]
	response[headerLength+1], response[headerLength+3] = response[headerLength+3], response[headerLength+1]

	response[10], response[11] = 0, 0
	binary.BigEndian.PutUint16(response[10:12], internetChecksum(response[:headerLength]))
	if response[headerLength+6] != 0 || response[headerLength+7] != 0 {
		response[headerLength+6], response[headerLength+7] = 0, 0
		checksumInput := make([]byte, 0, 12+udpLength)
		checksumInput = append(checksumInput, response[12:20]...)
		checksumInput = append(checksumInput, 0, 17)
		lengthBytes := make([]byte, 2)
		binary.BigEndian.PutUint16(lengthBytes, uint16(udpLength))
		checksumInput = append(checksumInput, lengthBytes...)
		checksumInput = append(checksumInput, response[headerLength:headerLength+udpLength]...)
		binary.BigEndian.PutUint16(response[headerLength+6:headerLength+8], internetChecksum(checksumInput))
	}
	return response, nil
}

func internetChecksum(data []byte) uint16 {
	var sum uint32
	for len(data) >= 2 {
		sum += uint32(binary.BigEndian.Uint16(data[:2]))
		data = data[2:]
	}
	if len(data) == 1 {
		sum += uint32(data[0]) << 8
	}
	for sum>>16 != 0 {
		sum = (sum & 0xffff) + (sum >> 16)
	}
	return ^uint16(sum)
}
