package cn.zju.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealVpnLifecycleTest {
    @Test
    fun bridgeFailureSurvivesCleanupAndLaterStoppedCallback() {
        val lifecycle = RealVpnLifecycle()
        assertTrue(lifecycle.beginSession())

        lifecycle.recordFailure("vpnSetupFailed", "Unable to prepare the authenticated aTrust VPN")
        assertFalse(lifecycle.acceptsProgress())
        assertTrue(lifecycle.beginCleanup())

        val outcome = lifecycle.terminalOutcome() as RealVpnTerminalOutcome.Error
        assertEquals("vpnSetupFailed", outcome.failure.code)
        assertEquals("Unable to prepare the authenticated aTrust VPN", outcome.failure.message)
    }

    @Test
    fun startupFailureWinsOverLaterDestroyCleanup() {
        val lifecycle = RealVpnLifecycle()
        assertTrue(lifecycle.beginSession())

        lifecycle.recordFailure("vpnStartFailed", "Android refused to establish the VPN interface")
        assertNull(lifecycle.recordUnexpectedDestruction())
        lifecycle.beginCleanup()

        val outcome = lifecycle.terminalOutcome() as RealVpnTerminalOutcome.Error
        assertEquals("vpnStartFailed", outcome.failure.code)
    }

    @Test
    fun revokeIsAnErrorRatherThanStopped() {
        val lifecycle = RealVpnLifecycle()
        assertTrue(lifecycle.beginSession())

        lifecycle.recordRevocation()
        lifecycle.beginCleanup()

        val outcome = lifecycle.terminalOutcome() as RealVpnTerminalOutcome.Error
        assertEquals("vpnRevoked", outcome.failure.code)
    }

    @Test
    fun onlyExplicitUserStopProducesStopped() {
        val lifecycle = RealVpnLifecycle()
        assertTrue(lifecycle.beginSession())

        lifecycle.requestUserStop()
        assertFalse(lifecycle.acceptsProgress())
        lifecycle.beginCleanup()

        assertEquals(RealVpnTerminalOutcome.Stopped, lifecycle.terminalOutcome())
    }

    @Test
    fun firstFailureWins() {
        val lifecycle = RealVpnLifecycle()
        assertTrue(lifecycle.beginSession())

        lifecycle.recordFailure("vpnTunReadFailed", "Unable to read the Android VPN interface")
        val retained = lifecycle.recordFailure("vpnServerReadFailed", "The server closed the connection")

        assertEquals("vpnTunReadFailed", retained?.code)
    }

    @Test
    fun userStopSuppressesLaterStartupFailure() {
        val lifecycle = RealVpnLifecycle()
        assertTrue(lifecycle.beginSession())

        lifecycle.requestUserStop()
        assertNull(lifecycle.recordFailure("tunEstablishTimeout", "Android VPN setup timed out"))
        lifecycle.beginCleanup()

        assertEquals(RealVpnTerminalOutcome.Stopped, lifecycle.terminalOutcome())
    }

    @Test
    fun tunWriteDiagnosticIsSafeAndVisibleInTheExistingMessage() {
        val message = realVpnErrorMessage(
            GoVpnEvent(
                state = "error",
                code = "vpnTunWriteFailed",
                message = "Unable to write aTrust data to the Android VPN interface",
                stage = "dataplane.tun.write",
                cause = "invalidPacket",
            ),
        )

        assertEquals(
            "Unable to write aTrust data to the Android VPN interface " +
                "(diagnostic: dataplane.tun.write/invalidPacket)",
            message,
        )
    }

    @Test
    fun unknownBridgeCauseIsNotShown() {
        val message = realVpnErrorMessage(
            GoVpnEvent(
                state = "error",
                code = "vpnTunWriteFailed",
                message = "Unable to write aTrust data to the Android VPN interface",
                stage = "dataplane.tun.write",
                cause = "server-response-that-must-not-reach-ui",
            ),
        )

        assertEquals("Unable to write aTrust data to the Android VPN interface", message)
    }

    @Test
    fun recoveryStopFailureRemainsTerminalAndPreventsRestart() {
        val lifecycle = RealVpnLifecycle()
        val coordinator = RealVpnRecoveryCoordinator()
        val initial = UnderlayNetworkSnapshot(
            revision = 0,
            networks = setOf(UnderlayNetworkFingerprint(networkHandle = 100)),
        )
        assertTrue(lifecycle.beginSession())
        assertTrue(coordinator.beginSession(initial))
        coordinator.onSessionActive(initial)
        coordinator.onNetworkChanged(
            UnderlayNetworkSnapshot(
                revision = 1,
                networks = setOf(UnderlayNetworkFingerprint(networkHandle = 101)),
            ),
        )
        coordinator.onDebounceElapsed(1)
        lifecycle.beginCleanup()

        lifecycle.recordFailure("stopTimeout", "Timed out waiting for VPN cleanup")
        coordinator.terminate()

        assertTrue(coordinator.onRecoveryStopCompleted(initial).isEmpty())
        val outcome = lifecycle.terminalOutcome() as RealVpnTerminalOutcome.Error
        assertEquals("stopTimeout", outcome.failure.code)
        assertEquals(
            "stopTimeout",
            lifecycle.recordFailure("vpnStartFailed", "late failure")?.code,
        )
    }
}
