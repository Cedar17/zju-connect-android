package io.github.cedar17.zjuconnect

import androidx.annotation.StringRes

internal enum class RealVpnNotificationKind {
    CONNECTING,
    CONNECTED,
    RECOVERING,
    WAITING_FOR_NETWORK,
    WAITING_FOR_AUTHENTICATION,
}

@StringRes
internal fun realVpnNotificationTextRes(kind: RealVpnNotificationKind): Int = when (kind) {
    RealVpnNotificationKind.CONNECTING -> R.string.notification_connecting
    RealVpnNotificationKind.CONNECTED -> R.string.notification_connected
    RealVpnNotificationKind.RECOVERING -> R.string.notification_recovering
    RealVpnNotificationKind.WAITING_FOR_NETWORK -> R.string.notification_waiting_network
    RealVpnNotificationKind.WAITING_FOR_AUTHENTICATION -> R.string.notification_waiting_authentication
}
