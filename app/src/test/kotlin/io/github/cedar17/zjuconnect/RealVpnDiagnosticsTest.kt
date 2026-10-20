package io.github.cedar17.zjuconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealVpnDiagnosticsTest {
    private val bridge = GoCoreBridge()

    @Test
    fun parserUsesSafeDefaultsWhenDiagnosticsAreAbsent() {
        val event = bridge.parseVpnEvent("""{"state":"active","message":"active"}""")
        assertEquals("active", event.state)
        assertNull(event.diagnostics)
    }

    @Test
    fun lifecycleParserReadsCounters() {
        val event = bridge.parseVpnEvent(
            """{"state":"stopped","diagnostics":{"tunReadPackets":3,"tunReadBytes":180,
                "l3WriteSuccesses":1,"resourceDrops":1}}""",
        )
        assertEquals(3L, event.diagnostics?.tunReadPackets)
        assertEquals(1L, event.diagnostics?.l3WriteSuccesses)
        assertEquals(1L, event.diagnostics?.resourceDrops)
    }

    @Test
    fun diagnosticLogContainsOnlyCounters() {
        val event = GoVpnEvent(
            state = "stopped", code = "", message = "stopped",
            diagnostics = GoVpnDiagnostics(tunReadPackets = 1, tunReadBytes = 60),
        )
        val log = realVpnDiagnosticLog(event).orEmpty()
        assertTrue(log.contains("tunRead=1/60"))
        for (forbidden in listOf("sourceIp", "destinationIp", "tcpFlags", "checksum", "password", "cookie", "sid", "deviceId", "signKey", "payload")) {
            assertFalse(log.contains(forbidden))
        }
    }
}
