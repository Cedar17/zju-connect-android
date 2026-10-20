package core

import (
	"encoding/binary"
	"testing"
)

func TestRealVpnPacketForwardingPolicy(t *testing.T) {
	packet := func(version, header, protocol byte, size, total int) []byte {
		p := make([]byte, size)
		p[0] = version<<4 | header
		p[9] = protocol
		binary.BigEndian.PutUint16(p[2:4], uint16(total))
		return p
	}
	tests := []struct {
		name           string
		packet         []byte
		valid, forward bool
	}{
		{"tcp", packet(4, 5, 6, 40, 40), true, true},
		{"udp", packet(4, 5, 17, 28, 28), true, true},
		{"options", packet(4, 6, 6, 44, 44), true, true},
		{"trailing bytes", packet(4, 5, 17, 32, 28), true, true},
		{"icmp", packet(4, 5, 1, 28, 28), true, false},
		{"unknown protocol", packet(4, 5, 99, 28, 28), true, false},
		{"ipv6", packet(6, 5, 6, 40, 40), false, false},
		{"short header", packet(4, 4, 6, 40, 40), false, false},
		{"header exceeds buffer", packet(4, 15, 6, 40, 40), false, false},
		{"total below header", packet(4, 6, 6, 40, 20), false, false},
		{"truncated", packet(4, 5, 6, 40, 41), false, false},
		// Preserve the existing IP-only validation, without adding TCP validation.
		{"no transport header", packet(4, 5, 6, 20, 20), true, true},
		{"empty", nil, false, false},
		{"short", []byte{0x45}, false, false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := isValidRealVpnIPv4Packet(tt.packet); got != tt.valid {
				t.Errorf("valid = %v, want %v", got, tt.valid)
			}
			if got := isForwardableRealVpnPacket(tt.packet); got != tt.forward {
				t.Errorf("forward = %v, want %v", got, tt.forward)
			}
		})
	}
}

func TestRealVpnPacketFilterDoesNotAllocate(t *testing.T) {
	packet := make([]byte, 40)
	packet[0], packet[9] = 0x45, 6
	binary.BigEndian.PutUint16(packet[2:4], 40)
	if allocations := testing.AllocsPerRun(100, func() { isForwardableRealVpnPacket(packet) }); allocations != 0 {
		t.Fatalf("allocations per filter = %v, want 0", allocations)
	}
}
