package core

import (
	"context"
	"encoding/binary"
	"errors"
	"net"
	"os"
	"sync/atomic"
	"syscall"
)

type realVpnDiagnosticsSnapshot struct {
	TunReadPackets     uint64 `json:"tunReadPackets"`
	TunReadBytes       uint64 `json:"tunReadBytes"`
	ForwardablePackets uint64 `json:"forwardablePackets"`
	FilteredPackets    uint64 `json:"filteredPackets"`
	L3WriteAttempts    uint64 `json:"l3WriteAttempts"`
	L3WriteSuccesses   uint64 `json:"l3WriteSuccesses"`
	ResourceDrops      uint64 `json:"resourceDrops"`
	L3ReadPackets      uint64 `json:"l3ReadPackets"`
	L3ReadBytes        uint64 `json:"l3ReadBytes"`
	L3InvalidPackets   uint64 `json:"l3InvalidPackets"`
	TunWriteAttempts   uint64 `json:"tunWriteAttempts"`
	TunWriteSuccesses  uint64 `json:"tunWriteSuccesses"`
	TunWriteBytes      uint64 `json:"tunWriteBytes"`
}

type realVpnDiagnostics struct {
	tunReadPackets     atomic.Uint64
	tunReadBytes       atomic.Uint64
	forwardablePackets atomic.Uint64
	filteredPackets    atomic.Uint64
	l3WriteAttempts    atomic.Uint64
	l3WriteSuccesses   atomic.Uint64
	resourceDrops      atomic.Uint64
	l3ReadPackets      atomic.Uint64
	l3ReadBytes        atomic.Uint64
	l3InvalidPackets   atomic.Uint64
	tunWriteAttempts   atomic.Uint64
	tunWriteSuccesses  atomic.Uint64
	tunWriteBytes      atomic.Uint64
}

func (d *realVpnDiagnostics) snapshot() realVpnDiagnosticsSnapshot {
	if d == nil {
		return realVpnDiagnosticsSnapshot{}
	}
	return realVpnDiagnosticsSnapshot{
		TunReadPackets:     d.tunReadPackets.Load(),
		TunReadBytes:       d.tunReadBytes.Load(),
		ForwardablePackets: d.forwardablePackets.Load(),
		FilteredPackets:    d.filteredPackets.Load(),
		L3WriteAttempts:    d.l3WriteAttempts.Load(),
		L3WriteSuccesses:   d.l3WriteSuccesses.Load(),
		ResourceDrops:      d.resourceDrops.Load(),
		L3ReadPackets:      d.l3ReadPackets.Load(),
		L3ReadBytes:        d.l3ReadBytes.Load(),
		L3InvalidPackets:   d.l3InvalidPackets.Load(),
		TunWriteAttempts:   d.tunWriteAttempts.Load(),
		TunWriteSuccesses:  d.tunWriteSuccesses.Load(),
		TunWriteBytes:      d.tunWriteBytes.Load(),
	}
}

// These checks preserve the previous forwarding policy without parsing endpoints,
// transport headers or checksums. Inbound validation is diagnostic only.
func isValidRealVpnIPv4Packet(packet []byte) bool {
	if len(packet) < 20 || packet[0]>>4 != 4 {
		return false
	}
	headerLength := int(packet[0]&0x0f) * 4
	if headerLength < 20 || headerLength > len(packet) {
		return false
	}
	totalLength := int(binary.BigEndian.Uint16(packet[2:4]))
	return totalLength >= headerLength && totalLength <= len(packet)
}

func isForwardableRealVpnPacket(packet []byte) bool {
	return isValidRealVpnIPv4Packet(packet) && (packet[9] == 6 || packet[9] == 17)
}

func classifyL3WriteError(err error) string {
	switch {
	case errors.Is(err, net.ErrClosed), errors.Is(err, os.ErrClosed):
		return "connectionClosed"
	case errors.Is(err, context.DeadlineExceeded), errors.Is(err, os.ErrDeadlineExceeded):
		return "timeout"
	default:
		return "io"
	}
}

// classifyTunWriteError exposes only stable, non-sensitive diagnostics across
// the Go/Android callback boundary. The underlying error can contain device
// details and must remain local to the process.
func classifyTunWriteError(err error) string {
	switch {
	case errors.Is(err, os.ErrClosed), errors.Is(err, syscall.EBADF):
		return "fdClosed"
	case errors.Is(err, syscall.EAGAIN), errors.Is(err, syscall.EWOULDBLOCK):
		return "wouldBlock"
	case errors.Is(err, syscall.EMSGSIZE):
		return "packetTooLarge"
	case errors.Is(err, syscall.EINVAL):
		return "invalidPacket"
	case errors.Is(err, syscall.EIO):
		return "tunUnavailable"
	default:
		return "io"
	}
}
