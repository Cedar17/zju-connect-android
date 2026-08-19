package io.github.cedar17.zjuconnect

import org.junit.Assert.assertEquals
import org.junit.Test

class RealVpnNotificationPolicyTest {
    @Test
    fun foregroundKindsMapToStatusTextResources() {
        val states = listOf(
            RealVpnNotificationKind.CONNECTING to R.string.notification_connecting,
            RealVpnNotificationKind.CONNECTED to R.string.notification_connected,
            RealVpnNotificationKind.RECOVERING to R.string.notification_recovering,
            RealVpnNotificationKind.WAITING_FOR_NETWORK to R.string.notification_waiting_network,
            RealVpnNotificationKind.WAITING_FOR_AUTHENTICATION to R.string.notification_waiting_authentication,
        )

        states.forEach { (kind, textRes) ->
            assertEquals(textRes, realVpnNotificationTextRes(kind))
        }
    }
}
