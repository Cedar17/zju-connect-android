package core

import (
	"encoding/binary"
	"encoding/json"
	"net"
	"os"
	"sync"
	"testing"
	"time"
)

type recordingSocketProtector struct {
	mu     sync.Mutex
	calls  int
	result bool
}

func (p *recordingSocketProtector) Protect(_ int) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.calls++
	return p.result
}

func (p *recordingSocketProtector) callCount() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.calls
}

type testEventCollector struct {
	events chan string
}

func (c *testEventCollector) OnEvent(eventJSON string) {
	c.events <- eventJSON
}

func waitForTestState(t *testing.T, collector *testEventCollector, state string) testStatePayload {
	t.Helper()
	deadline := time.NewTimer(3 * time.Second)
	defer deadline.Stop()
	for {
		select {
		case eventJSON := <-collector.events:
			var event testStatePayload
			if err := json.Unmarshal([]byte(eventJSON), &event); err != nil {
				t.Fatalf("invalid event: %v", err)
			}
			if event.State == state {
				return event
			}
		case <-deadline.C:
			t.Fatalf("timed out waiting for state %q", state)
		}
	}
}

func TestReflectUDPv4PacketSwapsEndpointsAndChecksums(t *testing.T) {
	packet := buildMarkedPacket()
	reflected, err := reflectUDPv4Packet(packet)
	if err != nil {
		t.Fatalf("reflectUDPv4Packet returned error: %v", err)
	}

	if got, want := net.IP(reflected[12:16]).String(), "192.0.2.1"; got != want {
		t.Errorf("source IP = %s, want %s", got, want)
	}
	if got, want := net.IP(reflected[16:20]).String(), "10.255.0.2"; got != want {
		t.Errorf("destination IP = %s, want %s", got, want)
	}
	if got, want := binary.BigEndian.Uint16(reflected[20:22]), uint16(34890); got != want {
		t.Errorf("source port = %d, want %d", got, want)
	}
	if got, want := binary.BigEndian.Uint16(reflected[22:24]), uint16(49152); got != want {
		t.Errorf("destination port = %d, want %d", got, want)
	}
	if got := internetChecksum(reflected[:20]); got != 0 {
		t.Errorf("IPv4 checksum = %#x, want zero-sum", got)
	}

	udpLength := int(binary.BigEndian.Uint16(reflected[24:26]))
	pseudo := append([]byte{}, reflected[12:20]...)
	pseudo = append(pseudo, 0, 17)
	lengthBytes := make([]byte, 2)
	binary.BigEndian.PutUint16(lengthBytes, uint16(udpLength))
	pseudo = append(pseudo, lengthBytes...)
	pseudo = append(pseudo, reflected[20:20+udpLength]...)
	if got := internetChecksum(pseudo); got != 0 {
		t.Errorf("UDP checksum = %#x, want zero-sum", got)
	}
}

func TestStartStopDataPlaneProtectsSocketAndIsIdempotent(t *testing.T) {
	readEnd, writeEnd, err := os.Pipe()
	if err != nil {
		t.Fatalf("os.Pipe: %v", err)
	}
	defer writeEnd.Close()

	protector := &recordingSocketProtector{result: true}
	collector := &testEventCollector{events: make(chan string, 32)}
	StartTestDataPlane(int(readEnd.Fd()), protector, collector)
	waitForTestState(t, collector, "socketProtected")

	if got := protector.callCount(); got != 1 {
		t.Fatalf("protect call count = %d, want 1", got)
	}

	duplicate := &testEventCollector{events: make(chan string, 4)}
	StartTestDataPlane(int(readEnd.Fd()), &recordingSocketProtector{result: true}, duplicate)
	event := waitForTestState(t, duplicate, "error")
	if event.Code != "alreadyRunning" {
		t.Fatalf("duplicate start code = %q, want alreadyRunning", event.Code)
	}

	StopTestDataPlane()
	waitForTestState(t, collector, "stopped")

	StopTestDataPlane()
}

func TestOpenTransportRejectsFailedProtect(t *testing.T) {
	readEnd, writeEnd, err := os.Pipe()
	if err != nil {
		t.Fatalf("os.Pipe: %v", err)
	}
	defer writeEnd.Close()

	session := &testDataPlaneSession{
		tunFD:     int(readEnd.Fd()),
		protector: &recordingSocketProtector{result: false},
		stopCh:    make(chan struct{}),
		doneCh:    make(chan struct{}),
	}
	if err := session.openTransport(); err == nil {
		t.Fatal("openTransport succeeded when protect returned false")
	}
	session.closeResources()
}

func buildMarkedPacket() []byte {
	payload := []byte(testMarker)
	packet := make([]byte, 20+8+len(payload))
	packet[0] = 0x45
	packet[8] = 64
	packet[9] = 17
	copy(packet[12:16], []byte{10, 255, 0, 2})
	copy(packet[16:20], []byte{192, 0, 2, 1})
	binary.BigEndian.PutUint16(packet[20:22], 49152)
	binary.BigEndian.PutUint16(packet[22:24], 34890)
	binary.BigEndian.PutUint16(packet[24:26], uint16(8+len(payload)))
	copy(packet[28:], payload)
	binary.BigEndian.PutUint16(packet[2:4], uint16(len(packet)))
	binary.BigEndian.PutUint16(packet[10:12], internetChecksum(packet[:20]))

	pseudo := append([]byte{}, packet[12:20]...)
	pseudo = append(pseudo, 0, 17)
	lengthBytes := make([]byte, 2)
	binary.BigEndian.PutUint16(lengthBytes, uint16(8+len(payload)))
	pseudo = append(pseudo, lengthBytes...)
	pseudo = append(pseudo, packet[20:]...)
	binary.BigEndian.PutUint16(packet[26:28], internetChecksum(pseudo))
	return packet
}
