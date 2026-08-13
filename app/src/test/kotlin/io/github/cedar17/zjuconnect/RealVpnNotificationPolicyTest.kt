package io.github.cedar17.zjuconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        )

        states.forEach { (kind, text) ->
            val content = realVpnNotificationContent(kind)
            assertEquals("ZJU Connect", content.title)
            assertEquals(text, content.text)
            assertTrue(content.ongoing)
        }
    }

    @Test
    fun terminalFailureIsClearableAndUserStopNeverPublishesIt() {
        val terminal = realVpnNotificationContent(RealVpnNotificationKind.TERMINAL_FAILURE)
        assertEquals("VPN 已断开", terminal.title)
        assertEquals("点按打开 ZJU Connect", terminal.text)
        assertFalse(terminal.ongoing)

        assertTrue(
            shouldPublishTerminalVpnNotification(
                RealVpnTerminalOutcome.Error(RealVpnFailure("failed", "failed")),
                notificationsEnabled = true,
            ),
        )
        assertFalse(
            shouldPublishTerminalVpnNotification(
                RealVpnTerminalOutcome.Stopped,
                notificationsEnabled = true,
            ),
        )
        assertFalse(
            shouldPublishTerminalVpnNotification(
                RealVpnTerminalOutcome.Error(RealVpnFailure("failed", "failed")),
                notificationsEnabled = false,
            ),
        )
    }
}
