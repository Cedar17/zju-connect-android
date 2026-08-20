package io.github.cedar17.zjuconnect

/** The Android-independent presentation contract for the Quick Settings tile. */
internal enum class QuickSettingsTileVisualState {
    ACTIVE,
    INACTIVE,
}

internal enum class QuickSettingsTileAction {
    CONNECT,
    DISCONNECT,
    OPEN_APP,
    NONE,
}

internal enum class QuickSettingsTileStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECOVERING,
    OPEN_APP_FOR_LOGIN,
    ALWAYS_ON_MANAGED,
}

internal data class QuickSettingsTilePresentation(
    val visualState: QuickSettingsTileVisualState,
    val action: QuickSettingsTileAction,
    val status: QuickSettingsTileStatus,
)

/**
 * Maps the service-owned VPN state to one direct, safe Quick Settings action.
 *
 * A startup reservation deliberately affects only idle/error presentation. An
 * established or recoverable VPN remains disconnectable even when its owner
 * still holds the reservation. Transient work stays inactive rather than
 * unavailable so System UI can deliver a click after it cached the tile while
 * this process was killed; a live reservation still accepts no second action.
 */
internal fun quickSettingsTilePresentation(
    vpnState: RealVpnUiState,
    entryInProgress: Boolean,
): QuickSettingsTilePresentation = when (vpnState.state) {
    "active" -> QuickSettingsTilePresentation(
        visualState = QuickSettingsTileVisualState.ACTIVE,
        action = QuickSettingsTileAction.DISCONNECT,
        status = QuickSettingsTileStatus.CONNECTED,
    )
    "recovering", "waitingForNetwork" -> QuickSettingsTilePresentation(
        visualState = QuickSettingsTileVisualState.ACTIVE,
        action = QuickSettingsTileAction.DISCONNECT,
        status = QuickSettingsTileStatus.RECOVERING,
    )
    "alwaysOnDisconnectBlocked" -> QuickSettingsTilePresentation(
        visualState = QuickSettingsTileVisualState.ACTIVE,
        action = QuickSettingsTileAction.DISCONNECT,
        status = QuickSettingsTileStatus.ALWAYS_ON_MANAGED,
    )
    "waitingForAuthentication" -> QuickSettingsTilePresentation(
        visualState = QuickSettingsTileVisualState.INACTIVE,
        action = QuickSettingsTileAction.OPEN_APP,
        status = QuickSettingsTileStatus.OPEN_APP_FOR_LOGIN,
    )
    "preparing", "attaching", "starting", "stopping" -> QuickSettingsTilePresentation(
        visualState = QuickSettingsTileVisualState.INACTIVE,
        action = QuickSettingsTileAction.NONE,
        status = QuickSettingsTileStatus.CONNECTING,
    )
    else -> if (entryInProgress) {
        QuickSettingsTilePresentation(
            visualState = QuickSettingsTileVisualState.INACTIVE,
            action = QuickSettingsTileAction.NONE,
            status = QuickSettingsTileStatus.CONNECTING,
        )
    } else {
        QuickSettingsTilePresentation(
            visualState = QuickSettingsTileVisualState.INACTIVE,
            action = QuickSettingsTileAction.CONNECT,
            status = QuickSettingsTileStatus.DISCONNECTED,
        )
    }
}

/**
 * A process-local lease for the one connection entry point. It intentionally
 * does not represent authentication or VPN state; those remain owned by the
 * existing Activity and RealVpnService flows.
 */
internal enum class ConnectionEntryOwner {
    ACTIVITY,
    TILE_SERVICE,
}

internal object ConnectionEntryArbiter {
    private val lock = Any()
    private var owner: ConnectionEntryOwner? = null

    fun tryBegin(nextOwner: ConnectionEntryOwner): Boolean = synchronized(lock) {
        if (owner != null) {
            false
        } else {
            owner = nextOwner
            true
        }
    }

    fun finish(expectedOwner: ConnectionEntryOwner) {
        synchronized(lock) {
            if (owner == expectedOwner) {
                owner = null
            }
        }
    }

    fun isInProgress(): Boolean = synchronized(lock) { owner != null }
}
