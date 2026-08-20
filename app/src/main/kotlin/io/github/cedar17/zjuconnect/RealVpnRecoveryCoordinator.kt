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

/** Immutable underlay observation captured immediately before a VPN session starts. */
internal data class UnderlaySessionStart(
    val snapshot: UnderlayNetworkSnapshot,
    val activeUnderlay: UnderlayNetworkFingerprint?,
)

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

    data class ScheduleDebounce(val generation: Long) : RealVpnRecoveryCommand

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
    private var sessionUnderlay: UnderlayNetworkFingerprint? = null
    private var nextDebounceGeneration = 0L
    private var pendingDebounceGeneration: Long? = null
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
    fun beginSession(
        snapshot: UnderlayNetworkSnapshot,
        activeUnderlay: UnderlayNetworkFingerprint?,
    ): Boolean {
        if (mode == Mode.TERMINATED) return false
        currentSnapshot = snapshot
        sessionUnderlay = activeUnderlay
        if (recoveryRequested && !snapshot.hasUsableNetwork) {
            mode = Mode.WAITING_FOR_NETWORK
            return false
        }
        mode = Mode.STARTING
        return true
    }

    fun onSessionActive(snapshot: UnderlayNetworkSnapshot): List<RealVpnRecoveryCommand> {
        if (mode == Mode.TERMINATED) return emptyList()
        currentSnapshot = snapshot
        if (!snapshot.hasUsableNetwork) {
            return stopForUnavailableNetwork()
        }
        if (!sessionUnderlayIsUsable(snapshot)) {
            return scheduleRestart()
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
            Mode.ACTIVE -> when {
                !snapshot.hasUsableNetwork -> stopForUnavailableNetwork()
                !sessionUnderlayIsUsable(snapshot) -> scheduleRestart()
                else -> emptyList()
            }
            Mode.DEBOUNCING_RESTART -> when {
                !snapshot.hasUsableNetwork -> stopForUnavailableNetwork()
                sessionUnderlayIsUsable(snapshot) -> {
                    recoveryRequested = false
                    mode = Mode.ACTIVE
                    cancelDebounce()
                }
                else -> emptyList()
            }
            Mode.WAITING_FOR_NETWORK -> {
                if (snapshot.hasUsableNetwork) {
                    recoveryRequested = true
                    mode = Mode.DEBOUNCING_START
                    listOf(scheduleDebounce())
                } else {
                    emptyList()
                }
            }
            Mode.DEBOUNCING_START -> {
                if (!snapshot.hasUsableNetwork) {
                    mode = Mode.WAITING_FOR_NETWORK
                    cancelDebounce()
                } else {
                    emptyList()
                }
            }
            Mode.STARTING, Mode.STOPPING_FOR_RECOVERY, Mode.IDLE -> emptyList()
            Mode.TERMINATED -> emptyList()
        }
    }

    fun onDebounceElapsed(generation: Long): List<RealVpnRecoveryCommand> {
        if (mode == Mode.TERMINATED || generation != pendingDebounceGeneration) {
            return emptyList()
        }
        pendingDebounceGeneration = null
        return when (mode) {
            Mode.DEBOUNCING_RESTART -> {
                if (currentSnapshot.hasUsableNetwork && sessionUnderlayIsUsable(currentSnapshot)) {
                    recoveryRequested = false
                    mode = Mode.ACTIVE
                    return emptyList()
                }
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
            sessionUnderlay = null
            mode = Mode.STARTING
            listOf(RealVpnRecoveryCommand.StartSession)
        } else {
            mode = Mode.WAITING_FOR_NETWORK
            emptyList()
        }
    }

    fun terminate(): List<RealVpnRecoveryCommand> {
        recoveryRequested = false
        sessionUnderlay = null
        pendingDebounceGeneration = null
        mode = Mode.TERMINATED
        return listOf(RealVpnRecoveryCommand.CancelDebounce)
    }

    private fun sessionUnderlayIsUsable(snapshot: UnderlayNetworkSnapshot): Boolean =
        sessionUnderlay?.let { underlay ->
            snapshot.networks.any { it == underlay && !it.suspended }
        } ?: true

    private fun scheduleRestart(): List<RealVpnRecoveryCommand> {
        recoveryRequested = true
        mode = Mode.DEBOUNCING_RESTART
        return listOf(scheduleDebounce())
    }

    private fun stopForUnavailableNetwork(): List<RealVpnRecoveryCommand> {
        recoveryRequested = true
        pendingDebounceGeneration = null
        mode = Mode.STOPPING_FOR_RECOVERY
        return listOf(
            RealVpnRecoveryCommand.CancelDebounce,
            RealVpnRecoveryCommand.StopSession,
        )
    }

    private fun scheduleDebounce(): RealVpnRecoveryCommand.ScheduleDebounce {
        val generation = ++nextDebounceGeneration
        pendingDebounceGeneration = generation
        return RealVpnRecoveryCommand.ScheduleDebounce(generation)
    }

    private fun cancelDebounce(): List<RealVpnRecoveryCommand> {
        pendingDebounceGeneration = null
        return listOf(RealVpnRecoveryCommand.CancelDebounce)
    }
}
