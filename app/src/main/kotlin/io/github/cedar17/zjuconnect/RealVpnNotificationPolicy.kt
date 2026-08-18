package io.github.cedar17.zjuconnect

import androidx.annotation.StringRes

internal enum class RealVpnNotificationKind {
    CONNECTING,
    CONNECTED,
    RECOVERING,
    WAITING_FOR_NETWORK,
    WAITING_FOR_AUTHENTICATION,
}

internal data class RealVpnNotificationContent(
    @get:StringRes val titleRes: Int,
    @get:StringRes val textRes: Int,
    val ongoing: Boolean,
)

internal fun realVpnNotificationContent(kind: RealVpnNotificationKind): RealVpnNotificationContent = when (kind) {
    RealVpnNotificationKind.CONNECTING -> RealVpnNotificationContent(
        titleRes = R.string.notification_title,
        textRes = R.string.notification_connecting,
        ongoing = true,
    )
    RealVpnNotificationKind.CONNECTED -> RealVpnNotificationContent(
        titleRes = R.string.notification_title,
        textRes = R.string.notification_connected,
        ongoing = true,
    )
    RealVpnNotificationKind.RECOVERING -> RealVpnNotificationContent(
        titleRes = R.string.notification_title,
        textRes = R.string.notification_recovering,
        ongoing = true,
    )
    RealVpnNotificationKind.WAITING_FOR_NETWORK -> RealVpnNotificationContent(
        titleRes = R.string.notification_title,
        textRes = R.string.notification_waiting_network,
        ongoing = true,
    )
    RealVpnNotificationKind.WAITING_FOR_AUTHENTICATION -> RealVpnNotificationContent(
        titleRes = R.string.notification_title,
        textRes = R.string.notification_waiting_authentication,
        ongoing = true,
    )
}

internal data class RealVpnGuidanceNotificationContent(
    @get:StringRes val titleRes: Int,
    @get:StringRes val textRes: Int,
)

internal fun realVpnAlwaysOnDisconnectGuidanceContent(): RealVpnGuidanceNotificationContent =
    RealVpnGuidanceNotificationContent(
        titleRes = R.string.notification_always_on_title,
        textRes = R.string.notification_always_on_text,
    )
