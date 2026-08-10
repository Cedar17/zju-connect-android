package cn.zju.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealVpnDiagnosticsTest {
    private val bridge = GoCoreBridge()

    @Test
    fun parserUsesSafeDefaultsWhenDiagnosticsAreAbsent() {
        val event = bridge.parseVpnEvent(
            """{"state":"active","code":"","message":"active","stage":"dataplane.active"}""",
        )

        assertEquals("active", event.state)
        assertNull(event.diagnostics)
        assertNull(event.packet)
    }

    @Test
    fun parserReadsCountersAndPacketMetadata() {
        val event = bridge.parseVpnEvent(
            """
            {
              "state":"diagnostic",
              "code":"",
              "message":"Real VPN data-plane observation",
              "stage":"dataplane.l3.write",
              "diagnostics":{
                "tunReadPackets":3,
                "tunReadBytes":180,
                "forwardablePackets":2,
                "filteredPackets":1,
                "l3WriteAttempts":2,
                "l3WriteSuccesses":1,
                "resourceDrops":1,
                "l3ReadPackets":1,
                "l3ReadBytes":60,
                "l3InvalidPackets":0,
                "tunWriteAttempts":1,
                "tunWriteSuccesses":1,
                "tunWriteBytes":60
              },
              "packet":{
                "sequence":4,
                "direction":"dataplane.l3.write",
                "ipVersion":4,
                "protocol":"tcp",
                "sourceIp":"private-ip",
                "destinationIp":"public-ip",
                "sourcePort":49152,
                "destinationPort":443,
                "length":60,
                "dataLength":8,
                "tcpFlags":24,
                "tcpSequence":1234,
                "tcpAcknowledgment":5678,
                "tcpWindow":65535,
                "ipChecksum":"valid",
                "transportChecksum":"valid",
                "valid":true
              }
            }
            """.trimIndent(),
        )

        assertEquals(3L, event.diagnostics?.tunReadPackets)
        assertEquals(1L, event.diagnostics?.l3WriteSuccesses)
        assertEquals(1L, event.diagnostics?.resourceDrops)
        assertEquals("public-ip", event.packet?.destinationIp)
        assertEquals(443, event.packet?.destinationPort)
        assertEquals(8, event.packet?.dataLength)
        assertEquals(24, event.packet?.tcpFlags)
        assertEquals(1234L, event.packet?.tcpSequence)
        assertEquals(5678L, event.packet?.tcpAcknowledgment)
        assertEquals(65535, event.packet?.tcpWindow)
        assertEquals("valid", event.packet?.ipChecksum)
        assertEquals("valid", event.packet?.transportChecksum)
        assertTrue(event.packet?.valid == true)
        assertFalse(event.packet?.truncated == true)
    }

    @Test
    fun diagnosticLogContainsStagesButNoPayloadOrAuthenticationFields() {
        val event = GoVpnEvent(
            state = "diagnostic",
            code = "",
            message = "Real VPN data-plane observation",
            stage = "dataplane.tun.read",
            diagnostics = GoVpnDiagnostics(tunReadPackets = 1, tunReadBytes = 60),
            packet = GoVpnPacketMetadata(
                sequence = 1,
                direction = "dataplane.tun.read",
                ipVersion = 4,
                protocol = "tcp",
                sourceIp = "private-ip",
                destinationIp = "public-ip",
                sourcePort = 49152,
                destinationPort = 443,
                length = 60,
                dataLength = 8,
                tcpFlags = 0x18,
                tcpSequence = 1234,
                tcpAcknowledgment = 5678,
                tcpWindow = 65535,
                ipChecksum = "valid",
                transportChecksum = "valid",
                valid = true,
            ),
        )

        val log = realVpnDiagnosticLog(event).orEmpty()
        assertTrue(log.contains("tunRead=1/60"))
        assertTrue(log.contains("direction=dataplane.tun.read"))
        assertTrue(log.contains("dataLen=8"))
        assertTrue(log.contains("seq=1234 ack=5678"))
        assertTrue(log.contains("checksums=valid/valid"))
        for (forbidden in listOf("password", "cookie", "sid", "deviceId", "signKey", "payload")) {
            assertFalse("log contained forbidden field $forbidden: $log", log.contains(forbidden))
        }
    }
}
