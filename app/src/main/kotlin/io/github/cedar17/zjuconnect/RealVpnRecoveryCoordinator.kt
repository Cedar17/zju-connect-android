package io.github.cedar17.zjuconnect

internal data class UnderlayNetworkFingerprint(
    val networkHandle: Long,
    val transportMask: Int = 0,
    val suspended: Boolean = false,
    val linkIdentityHash: Int = 0,
)

internal data class UnderlayNetworkSnapshot(
    val revision: Long = 0,
    val networks: Set<UnderlayNetworkFingerprint> = emptySet(),
) {
    val hasUsableNetwork: Boolean
        get() = networks.any { !it.suspended }
}

internal enum class RealVpnRecoveryPresentation {
    NONE,
    RECOVERING,
    WAITING_FOR_NETWORK,
}

internal fun combinedRealVpnRecoveryPresentation(
    underlay: RealVpnRecoveryPresentation,
    l3Recovering: Boolean,
): RealVpnRecoveryPresentation = when {
    underlay == RealVpnRecoveryPresentation.WAITING_FOR_NETWORK ->
        RealVpnRecoveryPresentation.WAITING_FOR_NETWORK
    underlay == RealVpnRecoveryPresentation.RECOVERING || l3Recovering ->
        RealVpnRecoveryPresentation.RECOVERING
    else -> RealVpnRecoveryPresentation.NONE
}

internal sealed interface RealVpnRecoveryCommand {
    data object CancelDebounce : RealVpnRecoveryCommand

    data class ScheduleDebounce(val revision: Long) : RealVpnRecoveryCommand

    data object StopSession : RealVpnRecoveryCommand

    data object StartSession : RealVpnRecoveryCommand
}

/**
 * Android-free policy for coalescing underlay changes into serialized VPN restarts.
 *
 * The service owns timers and I/O. This object only decides which action is safe
 * after each ordered lifecycle/network observation.
 */
internal class RealVpnRecoveryCoordinator {
    internal enum class Mode {
        IDLE,
        STARTING,
        ACTIVE,
        DEBOUNCING_RESTART,
        STOPPING_FOR_RECOVERY,
        WAITING_FOR_NETWORK,
        DEBOUNCING_START,
        TERMINATED,
    }

    private var currentSnapshot = UnderlayNetworkSnapshot()
    private var startRevision = 0L
    private var stopRevision = 0L
    private var recoveryRequested = false

    var mode: Mode = Mode.IDLE
        private set

    val presentation: RealVpnRecoveryPresentation
        get() = when {
            !recoveryRequested -> RealVpnRecoveryPresentation.NONE
            !currentSnapshot.hasUsableNetwork -> RealVpnRecoveryPresentation.WAITING_FOR_NETWORK
            else -> RealVpnRecoveryPresentation.RECOVERING
        }

    val isStoppingForRecovery: Boolean
        get() = recoveryRequested && mode == Mode.STOPPING_FOR_RECOVERY

    /** Returns false only when a recovery start raced with another loss. */
    fun beginSession(snapshot: UnderlayNetworkSnapshot): Boolean {
        if (mode == Mode.TERMINATED) return false
        currentSnapshot = snapshot
        if (recoveryRequested && !snapshot.hasUsableNetwork) {
            mode = Mode.WAITING_FOR_NETWORK
            return false
        }
        startRevision = snapshot.revision
        mode = Mode.STARTING
        return true
    }

    fun onSessionActive(snapshot: UnderlayNetworkSnapshot): List<RealVpnRecoveryCommand> {
        if (mode == Mode.TERMINATED) return emptyList()
        currentSnapshot = snapshot
        if (!snapshot.hasUsableNetwork) {
            recoveryRequested = true
            stopRevision = snapshot.revision
            mode = Mode.STOPPING_FOR_RECOVERY
            return listOf(
                RealVpnRecoveryCommand.CancelDebounce,
                RealVpnRecoveryCommand.StopSession,
            )
        }
        if (snapshot.revision != startRevision) {
            recoveryRequested = true
            mode = Mode.DEBOUNCING_RESTART
            return listOf(RealVpnRecoveryCommand.ScheduleDebounce(snapshot.revision))
        }
        recoveryRequested = false
        mode = Mode.ACTIVE
        return emptyList()
    }

    fun onNetworkChanged(snapshot: UnderlayNetworkSnapshot): List<RealVpnRecoveryCommand> {
        if (mode == Mode.TERMINATED || snapshot.revision == currentSnapshot.revision) {
            return emptyList()
        }
        currentSnapshot = snapshot
        return when (mode) {
            Mode.ACTIVE, Mode.DEBOUNCING_RESTART -> {
                recoveryRequested = true
                if (snapshot.hasUsableNetwork) {
                    mode = Mode.DEBOUNCING_RESTART
                    listOf(RealVpnRecoveryCommand.ScheduleDebounce(snapshot.revision))
                } else {
                    stopRevision = snapshot.revision
                    mode = Mode.STOPPING_FOR_RECOVERY
                    listOf(
                        RealVpnRecoveryCommand.CancelDebounce,
                        RealVpnRecoveryCommand.StopSession,
                    )
                }
            }
            Mode.WAITING_FOR_NETWORK, Mode.DEBOUNCING_START -> {
                recoveryRequested = true
                if (snapshot.hasUsableNetwork) {
                    mode = Mode.DEBOUNCING_START
                    listOf(RealVpnRecoveryCommand.ScheduleDebounce(snapshot.revision))
                } else {
                    mode = Mode.WAITING_FOR_NETWORK
                    listOf(RealVpnRecoveryCommand.CancelDebounce)
                }
            }
            // A change during stop/start is compared with startRevision after
            // the operation completes, producing at most one follow-up cycle.
            Mode.STARTING, Mode.STOPPING_FOR_RECOVERY, Mode.IDLE -> emptyList()
            Mode.TERMINATED -> emptyList()
        }
    }

    fun onDebounceElapsed(revision: Long): List<RealVpnRecoveryCommand> {
        if (mode == Mode.TERMINATED || revision != currentSnapshot.revision) {
            return emptyList()
        }
        return when (mode) {
            Mode.DEBOUNCING_RESTART -> {
                stopRevision = currentSnapshot.revision
                mode = Mode.STOPPING_FOR_RECOVERY
                listOf(RealVpnRecoveryCommand.StopSession)
            }
            Mode.DEBOUNCING_START -> {
                if (!currentSnapshot.hasUsableNetwork) {
                    mode = Mode.WAITING_FOR_NETWORK
                    emptyList()
                } else {
                    mode = Mode.STARTING
                    listOf(RealVpnRecoveryCommand.StartSession)
                }
            }
            else -> emptyList()
        }
    }

    fun onRecoveryStopCompleted(snapshot: UnderlayNetworkSnapshot): List<RealVpnRecoveryCommand> {
        if (mode == Mode.TERMINATED) return emptyList()
        currentSnapshot = snapshot
        recoveryRequested = true
        return if (snapshot.hasUsableNetwork) {
            if (snapshot.revision != stopRevision) {
                mode = Mode.DEBOUNCING_START
                listOf(RealVpnRecoveryCommand.ScheduleDebounce(snapshot.revision))
            } else {
                mode = Mode.STARTING
                listOf(RealVpnRecoveryCommand.StartSession)
            }
        } else {
            mode = Mode.WAITING_FOR_NETWORK
            emptyList()
        }
    }

    fun terminate(): List<RealVpnRecoveryCommand> {
        recoveryRequested = false
        mode = Mode.TERMINATED
        return listOf(RealVpnRecoveryCommand.CancelDebounce)
    }
}
