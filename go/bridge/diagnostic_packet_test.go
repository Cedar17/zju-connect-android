package core

import "encoding/binary"

const diagnosticMarker = "zju-connect-diagnostic-v1"

func buildMarkedPacket() []byte {
	payload := []byte(diagnosticMarker)
	packet := make([]byte, 20+8+len(payload))
	packet[0] = 0x45
	packet[8] = 64
	packet[9] = 17
	copy(packet[12:16], []byte{192, 168, 255, 2})
	copy(packet[16:20], []byte{192, 168, 255, 1})
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
