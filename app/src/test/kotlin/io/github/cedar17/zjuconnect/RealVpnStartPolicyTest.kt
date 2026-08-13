package io.github.cedar17.zjuconnect

import org.junit.Assert.assertEquals
import org.junit.Test

class RealVpnStartPolicyTest {
    @Test
    fun onlyExplicitManualMarkerIsManual() {
        assertEquals(
            RealVpnStartMode.MANUAL,
            classifyRealVpnStart(
                action = "manual-action",
                manualStartAction = "manual-action",
                startSource = REAL_VPN_START_SOURCE_MANUAL,
            ),
        )
        assertEquals(
            RealVpnStartMode.ALWAYS_ON,
            classifyRealVpnStart(
                action = "android.net.VpnService",
                manualStartAction = "manual-action",
                startSource = null,
            ),
        )
        assertEquals(
            RealVpnStartMode.ALWAYS_ON,
            classifyRealVpnStart(
                action = null,
                manualStartAction = "manual-action",
                startSource = null,
            ),
        )
    }

    @Test
    fun restartPolicyIsStickyOnlyForSystemStarts() {
        assertEquals(
            RealVpnRestartPolicy.START_NOT_STICKY,
            realVpnRestartPolicy(RealVpnStartMode.MANUAL),
        )
        assertEquals(
            RealVpnRestartPolicy.START_STICKY,
            realVpnRestartPolicy(RealVpnStartMode.ALWAYS_ON),
        )
    }

    @Test
    fun systemAlwaysOnBlocksOnlyUserDisconnectRequests() {
        assertEquals(true, shouldBlockAlwaysOnDisconnect(true, true))
        assertEquals(false, shouldBlockAlwaysOnDisconnect(false, true))
        assertEquals(false, shouldBlockAlwaysOnDisconnect(true, false))
    }
}
