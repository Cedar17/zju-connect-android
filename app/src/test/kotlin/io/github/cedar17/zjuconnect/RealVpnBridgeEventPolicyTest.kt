package io.github.cedar17.zjuconnect

import org.junit.Assert.assertEquals
import org.junit.Test

class RealVpnBridgeEventPolicyTest {
    @Test
    fun l3RecoveryEventsAreProgressRatherThanTerminalErrors() {
        assertEquals(
            RealVpnBridgeEventKind.L3_RECOVERING,
            classifyRealVpnBridgeEvent(
                GoVpnEvent(
                    state = "recovering",
                    code = "l3Reconnecting",
                    message = "recovering",
                    stage = "dataplane.l3.reconnect",
                ),
            ),
        )
        assertEquals(
            RealVpnBridgeEventKind.L3_RECOVERED,
            classifyRealVpnBridgeEvent(
                GoVpnEvent(
                    state = "active",
                    code = "",
                    message = "recovered",
                    stage = "dataplane.l3.recovered",
                ),
            ),
        )
    }

    @Test
    fun permanentL3FailureRemainsTerminal() {
        assertEquals(
            RealVpnBridgeEventKind.TERMINAL_ERROR,
            classifyRealVpnBridgeEvent(
                GoVpnEvent(
                    state = "error",
                    code = "vpnSessionInvalid",
                    message = "invalid",
                    stage = "dataplane.l3.reconnect",
                ),
            ),
        )
    }
}
