package io.github.cedar17.zjuconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealVpnNotificationPolicyTest {
    @Test
    fun foregroundStatesStayOngoingAndExposeOnlyStatusText() {
        val states = listOf(
            RealVpnNotificationKind.CONNECTING to R.string.notification_connecting,
            RealVpnNotificationKind.CONNECTED to R.string.notification_connected,
            RealVpnNotificationKind.RECOVERING to R.string.notification_recovering,
            RealVpnNotificationKind.WAITING_FOR_NETWORK to R.string.notification_waiting_network,
            RealVpnNotificationKind.WAITING_FOR_AUTHENTICATION to R.string.notification_waiting_authentication,
        )

        states.forEach { (kind, textRes) ->
            val content = realVpnNotificationContent(kind)
            assertEquals(R.string.notification_title, content.titleRes)
            assertEquals(textRes, content.textRes)
            assertTrue(content.ongoing)
        }
    }

    @Test
    fun alwaysOnDisconnectUsesOneShotSettingsGuidance() {
        val content = realVpnAlwaysOnDisconnectGuidanceContent()

        assertEquals(R.string.notification_always_on_title, content.titleRes)
        assertEquals(R.string.notification_always_on_text, content.textRes)
    }
}
