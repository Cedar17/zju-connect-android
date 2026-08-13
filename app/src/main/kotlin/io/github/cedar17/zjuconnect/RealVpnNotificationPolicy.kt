package io.github.cedar17.zjuconnect

internal enum class RealVpnNotificationKind {
    CONNECTING,
    CONNECTED,
    RECOVERING,
    WAITING_FOR_NETWORK,
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
}
