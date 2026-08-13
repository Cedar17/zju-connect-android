package io.github.cedar17.zjuconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealVpnNotificationPolicyTest {
    @Test
    fun foregroundStatesStayOngoingAndExposeOnlyStatusText() {
        val states = listOf(
            RealVpnNotificationKind.CONNECTING to "正在连接浙江大学 VPN",
            RealVpnNotificationKind.CONNECTED to "已连接到浙江大学 VPN",
            RealVpnNotificationKind.RECOVERING to "正在恢复 VPN 连接",
            RealVpnNotificationKind.WAITING_FOR_NETWORK to "正在等待可用网络",
            RealVpnNotificationKind.WAITING_FOR_AUTHENTICATION to "需要打开 App 完成登录",
        )

        states.forEach { (kind, text) ->
            val content = realVpnNotificationContent(kind)
            assertEquals("ZJU Connect", content.title)
            assertEquals(text, content.text)
            assertTrue(content.ongoing)
        }
    }

    @Test
    fun alwaysOnDisconnectUsesOneShotSettingsGuidance() {
        val content = realVpnAlwaysOnDisconnectGuidanceContent()

        assertEquals("ZJU Connect 由系统管理", content.title)
        assertEquals("请先在系统 VPN 设置中关闭 Always-on", content.text)
    }
}
