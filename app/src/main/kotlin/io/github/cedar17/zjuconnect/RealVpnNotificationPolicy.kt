package io.github.cedar17.zjuconnect

internal enum class RealVpnNotificationKind {
    CONNECTING,
    CONNECTED,
    RECOVERING,
    WAITING_FOR_NETWORK,
    TERMINAL_FAILURE,
}

internal data class RealVpnNotificationContent(
    val title: String,
    val text: String,
    val ongoing: Boolean,
)

internal fun realVpnNotificationContent(kind: RealVpnNotificationKind): RealVpnNotificationContent = when (kind) {
    RealVpnNotificationKind.CONNECTING -> RealVpnNotificationContent(
        title = "ZJU Connect",
        text = "正在连接浙江大学 VPN",
        ongoing = true,
    )
    RealVpnNotificationKind.CONNECTED -> RealVpnNotificationContent(
        title = "ZJU Connect",
        text = "已连接到浙江大学 VPN",
        ongoing = true,
    )
    RealVpnNotificationKind.RECOVERING -> RealVpnNotificationContent(
        title = "ZJU Connect",
        text = "正在恢复 VPN 连接",
        ongoing = true,
    )
    RealVpnNotificationKind.WAITING_FOR_NETWORK -> RealVpnNotificationContent(
        title = "ZJU Connect",
        text = "正在等待可用网络",
        ongoing = true,
    )
    RealVpnNotificationKind.TERMINAL_FAILURE -> RealVpnNotificationContent(
        title = "VPN 已断开",
        text = "点按打开 ZJU Connect",
        ongoing = false,
    )
}

internal fun shouldPublishTerminalVpnNotification(
    outcome: RealVpnTerminalOutcome?,
    notificationsEnabled: Boolean,
): Boolean = notificationsEnabled && outcome is RealVpnTerminalOutcome.Error
