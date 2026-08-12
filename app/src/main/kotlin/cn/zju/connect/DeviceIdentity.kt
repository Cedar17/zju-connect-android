package cn.zju.connect

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

private const val DEVICE_ID_NAMESPACE = "cn.zju.connect/atrust-device-id/v1\u0000"

internal fun deriveAtrustDeviceID(androidID: String?): String? {
    val stableID = androidID?.takeIf(String::isNotBlank) ?: return null
    val digest = MessageDigest.getInstance("SHA-256")
        .digest((DEVICE_ID_NAMESPACE + stableID).toByteArray(Charsets.UTF_8))
    return digest.take(16).joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal class DeviceIdentityProvider(private val context: Context) {
    fun read(): String? = deriveAtrustDeviceID(
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID),
    )
}
