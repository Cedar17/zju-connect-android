package io.github.cedar17.zjuconnect

import android.content.Context
import android.content.SharedPreferences

/**
 * The experimental custom aTrust server option.
 *
 * The target defaults to ZJU's production endpoint and stays there unless the
 * user unlocks advanced settings and enables the option. Validation mirrors the
 * Go bridge: a bare host (hostname or IP literal) plus a concrete port, so a
 * pasted URL can only ever narrow down to host and port.
 */
data class CustomServerTarget(
    val host: String,
    val port: Int,
) {
    companion object {
        const val DEFAULT_HOST = "vpn.zju.edu.cn"
        const val DEFAULT_PORT = 443
        val DEFAULT = CustomServerTarget(DEFAULT_HOST, DEFAULT_PORT)
    }
}

data class CustomServerSettings(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = CustomServerTarget.DEFAULT_PORT,
) {
    /**
     * The server the next connection should use, or null when the custom
     * server is enabled but its stored values are not a usable host/port.
     */
    fun effectiveTarget(): CustomServerTarget? = when {
        !enabled -> CustomServerTarget.DEFAULT
        !isValidCustomServerHost(host) -> null
        port !in MIN_CUSTOM_SERVER_PORT..MAX_CUSTOM_SERVER_PORT -> null
        else -> CustomServerTarget(host, port)
    }
}

const val MIN_CUSTOM_SERVER_PORT = 1
const val MAX_CUSTOM_SERVER_PORT = 65535

/**
 * Normalizes user input down to a bare host, or null when nothing usable
 * remains. Accepts a pasted URL by stripping its scheme, path, credentials,
 * and explicit port, so "https://atrust.example.edu:8443/" becomes
 * "atrust.example.edu". IPv6 literals stay bracketed.
 */
fun normalizeCustomServerHost(raw: String): String? {
    var host = raw.trim()
    if (host.isEmpty()) return null
    // Strip "<scheme>://" before the first authority separator.
    val schemeEnd = host.indexOf("://")
    if (schemeEnd >= 0) host = host.substring(schemeEnd + 3)
    // Path, query, and fragment terminate the authority.
    val authorityEnd = host.indexOfFirst { it == '/' || it == '\\' || it == '?' || it == '#' }
    if (authorityEnd >= 0) host = host.substring(0, authorityEnd)
    // Userinfo precedes the host; everything through the last '@' is credentials.
    val atIndex = host.lastIndexOf('@')
    if (atIndex >= 0) host = host.substring(atIndex + 1)
    host = host.trim()
    if (host.isEmpty()) return null
    // An embedded explicit port is valid input but belongs to the port field.
    val isBracketedIpv6 = host.startsWith("[") && host.endsWith("]")
    if (!isBracketedIpv6) {
        val lastColon = host.lastIndexOf(':')
        if (lastColon >= 0) {
            val portPart = host.substring(lastColon + 1)
            val onlyOneColon = host.count { it == ':' } == 1
            if (onlyOneColon && portPart.toIntOrNull() != null) {
                host = host.substring(0, lastColon)
            }
        }
    }
    host = host.trim()
    if (host.isEmpty()) return null
    return host
}

fun parseCustomServerPort(raw: String): Int? = raw.trim().toIntOrNull()?.takeIf { it in MIN_CUSTOM_SERVER_PORT..MAX_CUSTOM_SERVER_PORT }

/**
 * The same host rules the Go bridge enforces: hostname labels, an IPv4
 * literal, or a bracketed IPv6 literal. A scheme, path, or credentials in the
 * stored value is rejected here and would be rejected again at connect time.
 */
fun isValidCustomServerHost(host: String): Boolean {
    if (host.isEmpty() || host.length > 253) return false
    var labelHost = host
    if (labelHost.startsWith("[") && labelHost.endsWith("]")) {
        labelHost = labelHost.substring(1, labelHost.length - 1)
    }
    // A dotted-quad-looking string must be a valid IPv4 literal; broken forms
    // like "10.0.0.256" never fall back to the (digit-permissive) hostname rule.
    if (labelHost.all { it.isDigit() || it == '.' } && labelHost.split('.').size == 4) {
        return labelHost.split('.').all { octet ->
            octet.isNotEmpty() && octet.length <= 3 && octet.all(Char::isDigit) &&
                octet.toInt() in 0..255 && (octet == "0" || !octet.startsWith("0"))
        }
    }
    if (labelHost.contains(':')) {
        // A string containing ':' can never be a DNS name, and InetAddress
        // parses numeric IPv6 literals without any lookup. The Go bridge
        // repeats this check authoritatively at connect time.
        return runCatching {
            java.net.InetAddress.getByName(labelHost) is java.net.Inet6Address
        }.getOrDefault(false)
    }
    return isHostnameLabelChain(labelHost)
}

private fun isHostnameLabelChain(host: String): Boolean {
    val trimmed = host.removeSuffix(".")
    if (trimmed.isEmpty()) return false
    return trimmed.split('.').all { label ->
        label.length in 1..63 && label.indices.all { index ->
            val char = label[index]
            char.isLetterOrDigit() || (char == '-' && index != 0 && index != label.length - 1)
        }
    }
}

/** Persists the advanced settings unlock and the custom server configuration. */
class CustomServerSettingsStore(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): CustomServerSettings = CustomServerSettings(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        host = preferences.getString(KEY_HOST, "").orEmpty(),
        port = preferences.getInt(KEY_PORT, CustomServerTarget.DEFAULT_PORT),
    )

    fun readUnlocked(): Boolean = preferences.getBoolean(KEY_ADVANCED_UNLOCKED, false)

    fun writeUnlocked(unlocked: Boolean): Boolean =
        preferences.edit().putBoolean(KEY_ADVANCED_UNLOCKED, unlocked).commit()

    fun write(settings: CustomServerSettings): Boolean =
        preferences.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_HOST, settings.host)
            .putInt(KEY_PORT, settings.port)
            .commit()

    private companion object {
        const val PREFERENCES_NAME = "advanced_settings"
        const val KEY_ADVANCED_UNLOCKED = "advanced_settings_unlocked"
        const val KEY_ENABLED = "custom_server_enabled"
        const val KEY_HOST = "custom_server_host"
        const val KEY_PORT = "custom_server_port"
    }
}
