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
              "stage":"prepare.setup",
              "cause":"timeout",
              "durationMs":30000
            }
            """.trimIndent(),
        )

        assertEquals("error", prepared.state)
        assertEquals("vpnPrepareTimeout", prepared.code)
        assertEquals("prepare.setup", prepared.stage)
        assertEquals("timeout", prepared.cause)
        assertEquals(30_000L, prepared.durationMillis)
    }
    @Test
    fun cachedPreparationRetainsOriginalSetupDuration() {
        val prepared = GoCoreBridge().parseVpnPrepared(
            """{"state":"prepared","reusedPrepared":true,"setupDurationMs":1750}""",
        )
        assertEquals(true, prepared.reusedPrepared)
        assertEquals(1750L, prepared.setupDurationMillis)
        assertEquals(0L, prepared.durationMillis)
    }
}
