package core

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"os"
	"sync"
	"sync/atomic"
	"syscall"
)

const realVpnPacketSampleLimit = 64

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

type realVpnPacketMetadata struct {
	Sequence          uint64 `json:"sequence"`
	Direction         string `json:"direction"`
	IPVersion         int    `json:"ipVersion"`
	Protocol          string `json:"protocol"`
	SourceIP          string `json:"sourceIp,omitempty"`
	DestinationIP     string `json:"destinationIp,omitempty"`
	SourcePort        uint16 `json:"sourcePort,omitempty"`
	DestinationPort   uint16 `json:"destinationPort,omitempty"`
	Length            int    `json:"length"`
	DataLength        int    `json:"dataLength,omitempty"`
	TCPFlags          uint8  `json:"tcpFlags,omitempty"`
	TCPSequence       uint32 `json:"tcpSequence,omitempty"`
	TCPAcknowledgment uint32 `json:"tcpAcknowledgment,omitempty"`
	TCPWindow         uint16 `json:"tcpWindow,omitempty"`
	IPChecksum        string `json:"ipChecksum,omitempty"`
	TransportChecksum string `json:"transportChecksum,omitempty"`
	Valid             bool   `json:"valid"`
	Truncated         bool   `json:"truncated,omitempty"`
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

	sampleMu       sync.Mutex
	sampledFlows   map[string]struct{}
	sampleSequence uint64
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

func (d *realVpnDiagnostics) samplePacket(direction string, packet []byte) *realVpnPacketMetadata {
	if d == nil {
		return nil
	}
	meta := inspectRealVpnPacket(direction, packet)
	key := fmt.Sprintf(
		"%s|%d|%s|%s|%d|%s|%d|%d|%d|%d|%d|%d|%d|%s|%s|%t|%t",
		meta.Direction,
		meta.IPVersion,
		meta.Protocol,
		meta.SourceIP,
		meta.SourcePort,
		meta.DestinationIP,
		meta.DestinationPort,
		meta.Length,
		meta.DataLength,
		meta.TCPFlags,
		meta.TCPSequence,
		meta.TCPAcknowledgment,
		meta.TCPWindow,
		meta.IPChecksum,
		meta.TransportChecksum,
		meta.Valid,
		meta.Truncated,
	)

	d.sampleMu.Lock()
	defer d.sampleMu.Unlock()
	if d.sampledFlows == nil {
		d.sampledFlows = make(map[string]struct{})
	}
	if _, exists := d.sampledFlows[key]; exists || len(d.sampledFlows) >= realVpnPacketSampleLimit {
		return nil
	}
	d.sampledFlows[key] = struct{}{}
	d.sampleSequence++
	meta.Sequence = d.sampleSequence
	return &meta
}

func inspectRealVpnPacket(direction string, packet []byte) realVpnPacketMetadata {
	meta := realVpnPacketMetadata{
		Direction: direction,
		Protocol:  "unknown",
		Length:    len(packet),
	}
	if len(packet) == 0 {
		return meta
	}
	meta.IPVersion = int(packet[0] >> 4)
	if meta.IPVersion != 4 || len(packet) < 20 {
		return meta
	}

	headerLength := int(packet[0]&0x0f) * 4
	if headerLength < 20 || len(packet) < headerLength {
		return meta
	}
	meta.SourceIP = net.IP(packet[12:16]).String()
	meta.DestinationIP = net.IP(packet[16:20]).String()
	totalLength := int(binary.BigEndian.Uint16(packet[2:4]))
	if totalLength < headerLength || totalLength > len(packet) {
		meta.Truncated = totalLength > len(packet)
		return meta
	}
	meta.IPChecksum = packetChecksumStatus(packet[:headerLength], 0)
	transportLength := totalLength - headerLength

	switch packet[9] {
	case 6:
		meta.Protocol = "tcp"
	case 17:
		meta.Protocol = "udp"
	case 1:
		meta.Protocol = "icmp"
	default:
		meta.Protocol = fmt.Sprintf("ip-%d", packet[9])
	}
	if (meta.Protocol == "tcp" || meta.Protocol == "udp") && totalLength >= headerLength+4 {
		meta.SourcePort = binary.BigEndian.Uint16(packet[headerLength : headerLength+2])
		meta.DestinationPort = binary.BigEndian.Uint16(packet[headerLength+2 : headerLength+4])
	}
	if meta.Protocol == "tcp" && transportLength >= 20 {
		tcpHeaderLength := int(packet[headerLength+12]>>4) * 4
		if tcpHeaderLength >= 20 && tcpHeaderLength <= transportLength {
			meta.TCPFlags = packet[headerLength+13]
			meta.TCPSequence = binary.BigEndian.Uint32(packet[headerLength+4 : headerLength+8])
			meta.TCPAcknowledgment = binary.BigEndian.Uint32(packet[headerLength+8 : headerLength+12])
			meta.TCPWindow = binary.BigEndian.Uint16(packet[headerLength+14 : headerLength+16])
			meta.DataLength = transportLength - tcpHeaderLength
			meta.TransportChecksum = ipv4TransportChecksumStatus(packet, headerLength, totalLength)
		}
	} else if meta.Protocol == "udp" && transportLength >= 8 {
		udpLength := int(binary.BigEndian.Uint16(packet[headerLength+4 : headerLength+6]))
		if udpLength >= 8 && udpLength <= transportLength {
			meta.DataLength = udpLength - 8
			if packet[headerLength+6] == 0 && packet[headerLength+7] == 0 {
				meta.TransportChecksum = "omitted"
			} else {
				meta.TransportChecksum = ipv4TransportChecksumStatus(packet, headerLength, headerLength+udpLength)
			}
		}
	}
	meta.Valid = true
	return meta
}

func ipv4TransportChecksumStatus(packet []byte, headerLength, totalLength int) string {
	transportLength := totalLength - headerLength
	initial := checksumWordSum(packet[12:20]) + uint32(packet[9]) + uint32(transportLength)
	return packetChecksumStatus(packet[headerLength:totalLength], initial)
}

func packetChecksumStatus(data []byte, initial uint32) string {
	sum := initial + checksumWordSum(data)
	for sum>>16 != 0 {
		sum = (sum & 0xffff) + (sum >> 16)
	}
	if ^uint16(sum) == 0 {
		return "valid"
	}
	return "invalid"
}

func checksumWordSum(data []byte) uint32 {
	var sum uint32
	for len(data) >= 2 {
		sum += uint32(binary.BigEndian.Uint16(data[:2]))
		data = data[2:]
	}
	if len(data) == 1 {
		sum += uint32(data[0]) << 8
	}
	return sum
}

func isForwardableRealVpnPacket(packet []byte) bool {
	meta := inspectRealVpnPacket("", packet)
	return meta.Valid && (meta.Protocol == "tcp" || meta.Protocol == "udp")
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
