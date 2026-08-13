package io.github.cedar17.zjuconnect

internal enum class RealVpnNotificationKind {
    CONNECTING,
    CONNECTED,
    RECOVERING,
    WAITING_FOR_NETWORK,
    WAITING_FOR_AUTHENTICATION,
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
    RealVpnNotificationKind.WAITING_FOR_AUTHENTICATION -> RealVpnNotificationContent(
        title = "ZJU Connect",
        text = "需要打开 App 完成登录",
        ongoing = true,
    )
}

internal data class RealVpnGuidanceNotificationContent(
    val title: String,
    val text: String,
)

internal fun realVpnAlwaysOnDisconnectGuidanceContent(): RealVpnGuidanceNotificationContent =
    RealVpnGuidanceNotificationContent(
        title = "ZJU Connect 由系统管理",
        text = "请先在系统 VPN 设置中关闭 Always-on",
    )
