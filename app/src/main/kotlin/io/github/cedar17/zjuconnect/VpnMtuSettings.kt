package io.github.cedar17.zjuconnect

import android.content.Context
import android.content.SharedPreferences

/**
 * VPN interface MTU (experimental setting, advanced settings only).
 *
 * The default keeps upstream's tested value of 1400; deployments whose
 * gateway handles full-size packets (as the official client assumes with
 * its 1500) may raise it for slightly better throughput.
 */
data class VpnMtuSettings(
    val mtu: Int = DEFAULT_MTU,
) {
    fun isValid(): Boolean = mtu in MIN_MTU..MAX_MTU

    companion object {
        const val DEFAULT_MTU = 1400
        const val MIN_MTU = 576
        const val MAX_MTU = 1500
    }
}

class VpnMtuSettingsStore(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): VpnMtuSettings = VpnMtuSettings(
        mtu = preferences.getInt(KEY_MTU, VpnMtuSettings.DEFAULT_MTU),
    )

    fun write(settings: VpnMtuSettings): Boolean =
        preferences.edit().putInt(KEY_MTU, settings.mtu).commit()

    private companion object {
        const val PREFERENCES_NAME = "advanced_settings"
        const val KEY_MTU = "vpn_mtu"
    }
}

/** Out-of-range input parses to null; the caller keeps the last valid value. */
fun parseVpnMtu(raw: String): Int? =
    raw.trim().toIntOrNull()?.takeIf { it in VpnMtuSettings.MIN_MTU..VpnMtuSettings.MAX_MTU }
