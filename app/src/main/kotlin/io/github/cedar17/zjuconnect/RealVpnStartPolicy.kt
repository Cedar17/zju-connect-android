package io.github.cedar17.zjuconnect

/** Distinguishes an explicit app request from Android's system-owned start. */
internal enum class RealVpnStartMode {
    MANUAL,
    ALWAYS_ON,
}

internal enum class RealVpnRestartPolicy {
    START_NOT_STICKY,
    START_STICKY,
}

internal const val REAL_VPN_EXTRA_START_SOURCE =
    "io.github.cedar17.zjuconnect.extra.REAL_VPN_START_SOURCE"
internal const val REAL_VPN_START_SOURCE_MANUAL = "manual"

internal fun classifyRealVpnStart(
    action: String?,
    manualStartAction: String,
    startSource: String?,
): RealVpnStartMode =
    if (action == manualStartAction && startSource == REAL_VPN_START_SOURCE_MANUAL) {
        RealVpnStartMode.MANUAL
    } else {
        // Android starts an Always-on VpnService with the VpnService action and
        // no application-specific marker. A null intent is the START_STICKY
        // restart form and is treated the same way.
        RealVpnStartMode.ALWAYS_ON
    }

internal fun realVpnRestartPolicy(mode: RealVpnStartMode): RealVpnRestartPolicy =
    if (mode == RealVpnStartMode.ALWAYS_ON) {
        RealVpnRestartPolicy.START_STICKY
    } else {
        RealVpnRestartPolicy.START_NOT_STICKY
    }

internal fun shouldBlockAlwaysOnDisconnect(
    userInitiated: Boolean,
    systemAlwaysOnEnabled: Boolean,
): Boolean = userInitiated && systemAlwaysOnEnabled
