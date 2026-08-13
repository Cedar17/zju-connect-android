package io.github.cedar17.zjuconnect

internal data class BackgroundProtectionState(
    val notificationsEnabled: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
) {
    val complete: Boolean
        get() = notificationsEnabled && batteryOptimizationIgnored
}

internal fun shouldShowBackgroundProtection(
    phase: ConnectionPhase,
    protection: BackgroundProtectionState,
): Boolean = phase in setOf(ConnectionPhase.CONNECTED, ConnectionPhase.RECOVERING_VPN) && !protection.complete
