package io.github.cedar17.zjuconnect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundProtectionTest {
    @Test
    fun oneCardCoversEitherMissingProtection() {
        assertTrue(
            shouldShowBackgroundProtection(
                ConnectionPhase.CONNECTED,
                BackgroundProtectionState(
                    notificationsEnabled = false,
                    batteryOptimizationIgnored = true,
                ),
            ),
        )
        assertTrue(
            shouldShowBackgroundProtection(
                ConnectionPhase.RECOVERING_VPN,
                BackgroundProtectionState(
                    notificationsEnabled = true,
                    batteryOptimizationIgnored = false,
                ),
            ),
        )
    }

    @Test
    fun cardDisappearsWhenCompleteOrDisconnected() {
        assertFalse(
            shouldShowBackgroundProtection(
                ConnectionPhase.CONNECTED,
                BackgroundProtectionState(
                    notificationsEnabled = true,
                    batteryOptimizationIgnored = true,
                ),
            ),
        )
        assertFalse(
            shouldShowBackgroundProtection(
                ConnectionPhase.DISCONNECTED,
                BackgroundProtectionState(),
            ),
        )
    }
}
