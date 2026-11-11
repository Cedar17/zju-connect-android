package io.github.cedar17.zjuconnect

import android.content.Context
import android.content.SharedPreferences

/**
 * The experimental proxy-only mode: instead of a system VpnService, the app
 * serves the authenticated aTrust session as local SOCKS5/HTTP proxies so it
 * can coexist with another VPN app. Hidden behind the advanced settings
 * unlock; disabled by default.
 */
data class ProxySettings(
    val enabled: Boolean = false,
    val socksPort: Int = DEFAULT_SOCKS_PORT,
    val httpPort: Int = DEFAULT_HTTP_PORT,
) {
    /** Ports are valid when both are in range and distinct (0 disables HTTP). */
    fun isValid(): Boolean =
        socksPort in MIN_PROXY_PORT..MAX_PROXY_PORT &&
            (httpPort == 0 || (httpPort in MIN_PROXY_PORT..MAX_PROXY_PORT && httpPort != socksPort))

    companion object {
        const val DEFAULT_SOCKS_PORT = 11080
        const val DEFAULT_HTTP_PORT = 11081
        const val MIN_PROXY_PORT = 1
        const val MAX_PROXY_PORT = 65535
    }
}

/** Persists the proxy-only mode configuration next to the custom-server settings. */
class ProxySettingsStore(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): ProxySettings = ProxySettings(
        enabled = preferences.getBoolean(KEY_PROXY_MODE_ENABLED, false),
        socksPort = preferences.getInt(KEY_PROXY_SOCKS_PORT, ProxySettings.DEFAULT_SOCKS_PORT),
        httpPort = preferences.getInt(KEY_PROXY_HTTP_PORT, ProxySettings.DEFAULT_HTTP_PORT),
    )

    fun write(settings: ProxySettings): Boolean =
        preferences.edit()
            .putBoolean(KEY_PROXY_MODE_ENABLED, settings.enabled)
            .putInt(KEY_PROXY_SOCKS_PORT, settings.socksPort)
            .putInt(KEY_PROXY_HTTP_PORT, settings.httpPort)
            .commit()

    private companion object {
        const val PREFERENCES_NAME = "advanced_settings"
        const val KEY_PROXY_MODE_ENABLED = "proxy_mode_enabled"
        const val KEY_PROXY_SOCKS_PORT = "proxy_socks_port"
        const val KEY_PROXY_HTTP_PORT = "proxy_http_port"
    }
}

fun parseProxyPort(raw: String): Int? =
    raw.trim().toIntOrNull()?.takeIf { it in ProxySettings.MIN_PROXY_PORT..ProxySettings.MAX_PROXY_PORT }

/** The HTTP port additionally accepts 0, which disables the HTTP proxy. */
fun parseProxyHttpPort(raw: String): Int? =
    raw.trim().toIntOrNull()?.takeIf { it == 0 || it in ProxySettings.MIN_PROXY_PORT..ProxySettings.MAX_PROXY_PORT }
