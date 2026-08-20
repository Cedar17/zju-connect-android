package io.github.cedar17.zjuconnect

import org.junit.Assert.assertEquals
import org.junit.Test

class GoCoreBridgeTest {
    @Test
    fun preparationTimeoutParsesOnlyItsSafeMetadata() {
        val prepared = GoCoreBridge().parseVpnPrepared(
            """
            {
              "state":"error",
              "code":"vpnPrepareTimeout",
              "message":"Timed out while preparing the authenticated aTrust VPN",
              "stage":"prepare.nodeProbe",
              "cause":"timeout",
              "durationMs":30000
            }
            """.trimIndent(),
        )

        assertEquals("error", prepared.state)
        assertEquals("vpnPrepareTimeout", prepared.code)
        assertEquals("prepare.nodeProbe", prepared.stage)
        assertEquals("timeout", prepared.cause)
        assertEquals(30_000L, prepared.durationMillis)
    }
}
