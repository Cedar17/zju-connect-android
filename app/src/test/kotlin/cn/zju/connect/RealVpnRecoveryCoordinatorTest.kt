package cn.zju.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealVpnRecoveryCoordinatorTest {
    @Test
    fun initialSnapshotBecomesBaselineWithoutRestart() {
        val coordinator = RealVpnRecoveryCoordinator()
        val initial = snapshot(0, network(100))

        assertTrue(coordinator.beginSession(initial))
        assertTrue(coordinator.onSessionActive(initial).isEmpty())

        assertEquals(RealVpnRecoveryCoordinator.Mode.ACTIVE, coordinator.mode)
        assertEquals(RealVpnRecoveryPresentation.NONE, coordinator.presentation)
    }

    @Test
    fun networkChangeDuringStartupSchedulesRecoveryAfterActive() {
        val coordinator = RealVpnRecoveryCoordinator()
        val initial = snapshot(0, network(100))
        val changed = snapshot(1, network(101))

        assertTrue(coordinator.beginSession(initial))
        assertTrue(coordinator.onNetworkChanged(changed).isEmpty())

        assertEquals(
            listOf(RealVpnRecoveryCommand.ScheduleDebounce(1)),
            coordinator.onSessionActive(changed),
        )
        assertEquals(RealVpnRecoveryPresentation.RECOVERING, coordinator.presentation)
    }

    @Test
    fun callbackBurstReschedulesAndOnlyLatestRevisionStopsSession() {
        val coordinator = activeCoordinator(snapshot(0, network(100)))

        assertEquals(
            listOf(RealVpnRecoveryCommand.ScheduleDebounce(1)),
            coordinator.onNetworkChanged(snapshot(1, network(100), network(101))),
        )
        assertEquals(
            listOf(RealVpnRecoveryCommand.ScheduleDebounce(2)),
            coordinator.onNetworkChanged(snapshot(2, network(100), network(102))),
        )
        assertTrue(coordinator.onDebounceElapsed(1).isEmpty())
        assertEquals(
            listOf(RealVpnRecoveryCommand.StopSession),
            coordinator.onDebounceElapsed(2),
        )
    }

    @Test
    fun linkIdentityChangeOnSameNetworkTriggersRecovery() {
        val coordinator = activeCoordinator(snapshot(0, network(100, linkHash = 1)))

        val commands = coordinator.onNetworkChanged(snapshot(1, network(100, linkHash = 2)))

        assertEquals(listOf(RealVpnRecoveryCommand.ScheduleDebounce(1)), commands)
    }

    @Test
    fun losingEveryUnderlayStopsThenWaitsUntilNetworkReturns() {
        val coordinator = activeCoordinator(snapshot(0, network(100)))

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
            listOf(RealVpnRecoveryCommand.ScheduleDebounce(2)),
            coordinator.onNetworkChanged(snapshot(2, network(101))),
        )
        assertEquals(
            listOf(RealVpnRecoveryCommand.StartSession),
            coordinator.onDebounceElapsed(2),
        )
        assertTrue(coordinator.beginSession(snapshot(2, network(101))))
        assertTrue(coordinator.onSessionActive(snapshot(2, network(101))).isEmpty())
        assertEquals(RealVpnRecoveryCoordinator.Mode.ACTIVE, coordinator.mode)
    }

    @Test
    fun suspendedUnderlayIsTreatedAsUnavailable() {
        val coordinator = activeCoordinator(snapshot(0, network(100)))

        val commands = coordinator.onNetworkChanged(snapshot(1, network(100, suspended = true)))

        assertEquals(
            listOf(
                RealVpnRecoveryCommand.CancelDebounce,
                RealVpnRecoveryCommand.StopSession,
            ),
            commands,
        )
        assertEquals(RealVpnRecoveryPresentation.WAITING_FOR_NETWORK, coordinator.presentation)
    }

    @Test
    fun changeDuringRestartProducesAtMostOneFollowUpRecovery() {
        val coordinator = activeCoordinator(snapshot(0, network(100)))
        coordinator.onNetworkChanged(snapshot(1, network(101)))
        coordinator.onDebounceElapsed(1)

        assertTrue(coordinator.onNetworkChanged(snapshot(2, network(102))).isEmpty())
        assertEquals(
            listOf(RealVpnRecoveryCommand.ScheduleDebounce(2)),
            coordinator.onRecoveryStopCompleted(snapshot(2, network(102))),
        )
        assertEquals(
            listOf(RealVpnRecoveryCommand.StartSession),
            coordinator.onDebounceElapsed(2),
        )
        assertTrue(coordinator.beginSession(snapshot(2, network(102))))
        assertTrue(coordinator.onNetworkChanged(snapshot(3, network(103))).isEmpty())
        assertEquals(
            listOf(RealVpnRecoveryCommand.ScheduleDebounce(3)),
            coordinator.onSessionActive(snapshot(3, network(103))),
        )
        assertEquals(
            listOf(RealVpnRecoveryCommand.StopSession),
            coordinator.onDebounceElapsed(3),
        )
    }

    @Test
    fun terminationCancelsPendingRecoveryAndRejectsLaterStarts() {
        val coordinator = activeCoordinator(snapshot(0, network(100)))
        coordinator.onNetworkChanged(snapshot(1, network(101)))

        assertEquals(
            listOf(RealVpnRecoveryCommand.CancelDebounce),
            coordinator.terminate(),
        )
        assertTrue(coordinator.onDebounceElapsed(1).isEmpty())
        assertTrue(coordinator.onNetworkChanged(snapshot(2, network(102))).isEmpty())
        assertFalse(coordinator.beginSession(snapshot(2, network(102))))
    }

    private fun activeCoordinator(initial: UnderlayNetworkSnapshot): RealVpnRecoveryCoordinator =
        RealVpnRecoveryCoordinator().also { coordinator ->
            assertTrue(coordinator.beginSession(initial))
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
