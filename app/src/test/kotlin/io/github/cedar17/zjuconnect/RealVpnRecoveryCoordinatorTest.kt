package io.github.cedar17.zjuconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealVpnRecoveryCoordinatorTest {
    @Test
    fun combinedPresentationPrioritizesWaitingThenEitherRecoverySource() {
        assertEquals(
            RealVpnRecoveryPresentation.WAITING_FOR_NETWORK,
            combinedRealVpnRecoveryPresentation(
                RealVpnRecoveryPresentation.WAITING_FOR_NETWORK,
                l3Recovering = true,
            ),
        )
        assertEquals(
            RealVpnRecoveryPresentation.RECOVERING,
            combinedRealVpnRecoveryPresentation(
                RealVpnRecoveryPresentation.NONE,
                l3Recovering = true,
            ),
        )
        assertEquals(
            RealVpnRecoveryPresentation.RECOVERING,
            combinedRealVpnRecoveryPresentation(
                RealVpnRecoveryPresentation.RECOVERING,
                l3Recovering = false,
            ),
        )
        assertEquals(
            RealVpnRecoveryPresentation.NONE,
            combinedRealVpnRecoveryPresentation(
                RealVpnRecoveryPresentation.NONE,
                l3Recovering = false,
            ),
        )
    }

    @Test
    fun sessionAnchorBecomesBaselineWithoutRestart() {
        val wifi = network(100, linkHash = 1)
        val initial = snapshot(0, wifi)
        val coordinator = RealVpnRecoveryCoordinator()

        assertTrue(coordinator.beginSession(initial, wifi))
        assertTrue(coordinator.onSessionActive(initial).isEmpty())

        assertEquals(RealVpnRecoveryCoordinator.Mode.ACTIVE, coordinator.mode)
        assertEquals(RealVpnRecoveryPresentation.NONE, coordinator.presentation)
    }

    @Test
    fun healthySessionIgnoresBackupNetworkChurn() {
        val wifi = network(100, linkHash = 1)
        val cellular = network(101, linkHash = 1)
        val coordinator = activeCoordinator(snapshot(0, wifi), wifi)

        assertTrue(coordinator.onNetworkChanged(snapshot(1, wifi, cellular)).isEmpty())
        assertTrue(
            coordinator.onNetworkChanged(
                snapshot(2, wifi, cellular.copy(linkIdentityHash = 2)),
            ).isEmpty(),
        )
        assertTrue(coordinator.onNetworkChanged(snapshot(3, wifi)).isEmpty())

        assertEquals(RealVpnRecoveryCoordinator.Mode.ACTIVE, coordinator.mode)
        assertEquals(RealVpnRecoveryPresentation.NONE, coordinator.presentation)
    }

    @Test
    fun healthyCellularSessionDoesNotFollowNewWifiDefault() {
        val cellular = network(101, linkHash = 1)
        val wifi = network(100, linkHash = 1)
        val coordinator = activeCoordinator(snapshot(0, cellular), cellular)

        assertTrue(coordinator.onNetworkChanged(snapshot(1, cellular, wifi)).isEmpty())

        assertEquals(RealVpnRecoveryCoordinator.Mode.ACTIVE, coordinator.mode)
    }

    @Test
    fun sessionAnchorLossSchedulesOneRecoveryDespiteBackupChurn() {
        val wifi = network(100, linkHash = 1)
        val cellular = network(101, linkHash = 1)
        val coordinator = activeCoordinator(snapshot(0, wifi), wifi)

        assertEquals(
            listOf(RealVpnRecoveryCommand.ScheduleDebounce(1)),
            coordinator.onNetworkChanged(snapshot(1, cellular)),
        )
        assertTrue(
            coordinator.onNetworkChanged(
                snapshot(2, cellular.copy(linkIdentityHash = 2)),
            ).isEmpty(),
        )

        assertEquals(
            listOf(RealVpnRecoveryCommand.StopSession),
            coordinator.onDebounceElapsed(1),
        )
    }

    @Test
    fun linkIdentityChangeOnSessionAnchorTriggersRecovery() {
        val wifi = network(100, linkHash = 1)
        val coordinator = activeCoordinator(snapshot(0, wifi), wifi)

        assertEquals(
            listOf(RealVpnRecoveryCommand.ScheduleDebounce(1)),
            coordinator.onNetworkChanged(snapshot(1, wifi.copy(linkIdentityHash = 2))),
        )
    }

    @Test
    fun suspendedSessionAnchorRecoversThroughRemainingNetwork() {
        val wifi = network(100, linkHash = 1)
        val cellular = network(101, linkHash = 1)
        val coordinator = activeCoordinator(snapshot(0, wifi, cellular), wifi)

        assertEquals(
            listOf(RealVpnRecoveryCommand.ScheduleDebounce(1)),
            coordinator.onNetworkChanged(snapshot(1, wifi.copy(suspended = true), cellular)),
        )
    }

    @Test
    fun restoredSessionAnchorCancelsPendingRecovery() {
        val wifi = network(100, linkHash = 1)
        val cellular = network(101, linkHash = 1)
        val coordinator = activeCoordinator(snapshot(0, wifi), wifi)

        assertEquals(
            listOf(RealVpnRecoveryCommand.ScheduleDebounce(1)),
            coordinator.onNetworkChanged(snapshot(1, cellular)),
        )
        assertEquals(
            listOf(RealVpnRecoveryCommand.CancelDebounce),
            coordinator.onNetworkChanged(snapshot(2, wifi, cellular)),
        )
        assertTrue(coordinator.onDebounceElapsed(1).isEmpty())
        assertEquals(RealVpnRecoveryCoordinator.Mode.ACTIVE, coordinator.mode)
    }

    @Test
    fun losingEveryUnderlayStopsThenWaitsUntilNetworkReturns() {
        val wifi = network(100)
        val cellular = network(101)
        val coordinator = activeCoordinator(snapshot(0, wifi), wifi)

        assertEquals(
            listOf(
                RealVpnRecoveryCommand.CancelDebounce,
                RealVpnRecoveryCommand.StopSession,
            ),
            coordinator.onNetworkChanged(snapshot(1)),
        )
        assertEquals(RealVpnRecoveryPresentation.WAITING_FOR_NETWORK, coordinator.presentation)

        assertTrue(coordinator.onRecoveryStopCompleted(snapshot(1)).isEmpty())
        assertEquals(RealVpnRecoveryCoordinator.Mode.WAITING_FOR_NETWORK, coordinator.mode)

        assertEquals(
            listOf(RealVpnRecoveryCommand.ScheduleDebounce(1)),
            coordinator.onNetworkChanged(snapshot(2, cellular)),
        )
        assertEquals(
            listOf(RealVpnRecoveryCommand.StartSession),
            coordinator.onDebounceElapsed(1),
        )
        assertTrue(coordinator.beginSession(snapshot(2, cellular), cellular))
        assertTrue(coordinator.onSessionActive(snapshot(2, cellular)).isEmpty())
        assertEquals(RealVpnRecoveryCoordinator.Mode.ACTIVE, coordinator.mode)
    }

    @Test
    fun backupChurnDoesNotResetWaitingStartDebounce() {
        val wifi = network(100)
        val cellular = network(101, linkHash = 1)
        val coordinator = activeCoordinator(snapshot(0, wifi), wifi)

        coordinator.onNetworkChanged(snapshot(1))
        coordinator.onRecoveryStopCompleted(snapshot(1))
        assertEquals(
            listOf(RealVpnRecoveryCommand.ScheduleDebounce(1)),
            coordinator.onNetworkChanged(snapshot(2, cellular)),
        )
        assertTrue(
            coordinator.onNetworkChanged(
                snapshot(3, cellular.copy(linkIdentityHash = 2)),
            ).isEmpty(),
        )
        assertEquals(
            listOf(RealVpnRecoveryCommand.StartSession),
            coordinator.onDebounceElapsed(1),
        )
    }

    @Test
    fun recoveryStopRestartsImmediatelyWithUsableNetwork() {
        val wifi = network(100)
        val cellular = network(101)
        val coordinator = activeCoordinator(snapshot(0, wifi), wifi)

        coordinator.onNetworkChanged(snapshot(1, cellular))
        assertEquals(
            listOf(RealVpnRecoveryCommand.StopSession),
            coordinator.onDebounceElapsed(1),
        )

        assertEquals(
            listOf(RealVpnRecoveryCommand.StartSession),
            coordinator.onRecoveryStopCompleted(snapshot(2, cellular)),
        )
        assertTrue(coordinator.beginSession(snapshot(2, cellular), cellular))
        assertTrue(coordinator.onSessionActive(snapshot(2, cellular)).isEmpty())
    }

    @Test
    fun startupOnlyRestartsWhenTheSessionAnchorWasLost() {
        val wifi = network(100)
        val cellular = network(101)
        val healthyCoordinator = RealVpnRecoveryCoordinator()

        assertTrue(healthyCoordinator.beginSession(snapshot(0, wifi), wifi))
        assertTrue(healthyCoordinator.onNetworkChanged(snapshot(1, wifi, cellular)).isEmpty())
        assertTrue(healthyCoordinator.onSessionActive(snapshot(1, wifi, cellular)).isEmpty())

        val lostAnchorCoordinator = RealVpnRecoveryCoordinator()
        assertTrue(lostAnchorCoordinator.beginSession(snapshot(0, wifi), wifi))
        assertTrue(lostAnchorCoordinator.onNetworkChanged(snapshot(1, cellular)).isEmpty())
        assertEquals(
            listOf(RealVpnRecoveryCommand.ScheduleDebounce(1)),
            lostAnchorCoordinator.onSessionActive(snapshot(1, cellular)),
        )
    }

    @Test
    fun missingSessionAnchorUsesConservativeFallback() {
        val wifi = network(100)
        val cellular = network(101)
        val coordinator = RealVpnRecoveryCoordinator()

        assertTrue(coordinator.beginSession(snapshot(0, wifi), activeUnderlay = null))
        assertTrue(coordinator.onSessionActive(snapshot(0, wifi)).isEmpty())
        assertTrue(coordinator.onNetworkChanged(snapshot(1, cellular)).isEmpty())
        assertTrue(coordinator.onNetworkChanged(snapshot(2, wifi, cellular)).isEmpty())

        assertEquals(
            listOf(
                RealVpnRecoveryCommand.CancelDebounce,
                RealVpnRecoveryCommand.StopSession,
            ),
            coordinator.onNetworkChanged(snapshot(3)),
        )
    }

    @Test
    fun terminationCancelsPendingRecoveryAndRejectsLaterStarts() {
        val wifi = network(100)
        val cellular = network(101)
        val coordinator = activeCoordinator(snapshot(0, wifi), wifi)
        coordinator.onNetworkChanged(snapshot(1, cellular))

        assertEquals(
            listOf(RealVpnRecoveryCommand.CancelDebounce),
            coordinator.terminate(),
        )
        assertTrue(coordinator.onDebounceElapsed(1).isEmpty())
        assertTrue(coordinator.onNetworkChanged(snapshot(2, wifi)).isEmpty())
        assertFalse(coordinator.beginSession(snapshot(2, wifi), wifi))
    }

    private fun activeCoordinator(
        initial: UnderlayNetworkSnapshot,
        activeUnderlay: UnderlayNetworkFingerprint,
    ): RealVpnRecoveryCoordinator = RealVpnRecoveryCoordinator().also { coordinator ->
        assertTrue(coordinator.beginSession(initial, activeUnderlay))
        assertTrue(coordinator.onSessionActive(initial).isEmpty())
    }

    private fun snapshot(
        revision: Long,
        vararg networks: UnderlayNetworkFingerprint,
    ): UnderlayNetworkSnapshot = UnderlayNetworkSnapshot(revision, networks.toSet())

    private fun network(
        handle: Long,
        suspended: Boolean = false,
        linkHash: Int = 0,
    ): UnderlayNetworkFingerprint = UnderlayNetworkFingerprint(
        networkHandle = handle,
        suspended = suspended,
        linkIdentityHash = linkHash,
    )
}
