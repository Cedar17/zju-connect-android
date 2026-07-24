package cn.zju.connect

import cn.zju.connect.gocore.core.BridgeListener
import cn.zju.connect.gocore.core.Core
import cn.zju.connect.gocore.core.SocketProtector
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kotlin's deliberately small boundary to the generated gomobile classes.
 *
 * The AAR exposes JSON strings rather than the upstream Go object graph. This
 * keeps future authentication and VPN lifecycle changes isolated here.
 */
class GoCoreBridge {
    fun readBuildInfo(): GoBridgeEvent = parseEvent(Core.getBuildInfo())

    fun emitBuildInfo(onEvent: (GoBridgeEvent) -> Unit) {
        Core.emitBuildInfo(
            object : BridgeListener {
                override fun onEvent(eventJson: String) {
                    onEvent(parseEvent(eventJson))
                }
            },
        )
    }

    fun startTestDataPlane(
        tunFd: Long,
        socketProtector: SocketProtector,
        onEvent: (GoTestVpnEvent) -> Unit,
    ) {
        Core.startTestDataPlane(
            tunFd,
            socketProtector,
            object : BridgeListener {
                override fun onEvent(eventJson: String) {
                    onEvent(parseTestEvent(eventJson))
                }
            },
        )
    }

    fun stopTestDataPlane() {
        Core.stopTestDataPlane()
    }

    fun startAuthentication(onEvent: (GoAuthEvent) -> Unit): GoAuthEvent =
        parseAuthEvent(
            Core.startAuthentication(
                JSONObject()
                    .put("server", ZJU_ATRUST_SERVER)
                    .put("port", ZJU_ATRUST_PORT)
                    .toString(),
                authListener(onEvent),
            ),
        )

    fun submitAuthentication(
        response: JSONObject,
        onEvent: (GoAuthEvent) -> Unit,
    ): GoAuthEvent = parseAuthEvent(Core.submitAuthentication(response.toString()))

    fun pendingCaptchaImage(): ByteArray = Core.getPendingCaptchaImage()

    fun cancelAuthentication() {
        Core.cancelAuthentication()
    }

    fun clearAuthenticatedResult() {
        Core.clearAuthenticatedResult()
    }

    private fun authListener(onEvent: (GoAuthEvent) -> Unit): BridgeListener =
        object : BridgeListener {
            override fun onEvent(eventJson: String) {
                onEvent(parseAuthEvent(eventJson))
            }
        }

    private fun parseEvent(eventJson: String): GoBridgeEvent = runCatching {
        val event = JSONObject(eventJson)
        GoBridgeEvent(
            type = event.optString("type", "unknown"),
            upstreamCommit = event.optString("upstreamCommit", "unknown"),
            message = event.optString("message", "Go bridge response received"),
        )
    }.getOrElse {
        GoBridgeEvent(
            type = "invalidEvent",
            upstreamCommit = "unknown",
            message = "Go bridge returned an invalid structured event",
        )
    }

    private fun parseTestEvent(eventJson: String): GoTestVpnEvent = runCatching {
        val event = JSONObject(eventJson)
        GoTestVpnEvent(
            state = event.optString("state", "unknown"),
            code = event.optString("code", ""),
            message = event.optString("message", "Go test data-plane event"),
            packetsFromTun = event.optLong("packetsFromTun", 0),
            packetsToTun = event.optLong("packetsToTun", 0),
            bytesFromTun = event.optLong("bytesFromTun", 0),
            bytesToTun = event.optLong("bytesToTun", 0),
        )
    }.getOrElse {
        GoTestVpnEvent(
            state = "error",
            code = "invalidEvent",
            message = "Go bridge returned an invalid test data-plane event",
        )
    }

    private fun parseAuthEvent(eventJson: String): GoAuthEvent = runCatching {
        val event = JSONObject(eventJson)
        GoAuthEvent(
            type = event.optString("type", "unknown"),
            state = event.optString("state", "unknown"),
            code = event.optString("code", ""),
            message = event.optString("message", "Authentication response received"),
            authMethods = event.optJSONArray("authMethods").toAuthMethods(),
            phoneNumbers = event.optJSONArray("phoneNumbers").toStrings(),
            captchaWidth = event.optInt("captchaWidth", 0),
            captchaHeight = event.optInt("captchaHeight", 0),
            username = event.optString("username", ""),
        )
    }.getOrElse {
        GoAuthEvent(
            type = "error",
            state = "error",
            code = "invalidEvent",
            message = "Go bridge returned an invalid authentication event",
        )
    }

    private fun JSONArray?.toAuthMethods(): List<GoAuthMethod> =
        buildList {
            this@toAuthMethods?.let { methods ->
                for (index in 0 until methods.length()) {
                    val method = methods.optJSONObject(index) ?: continue
                    add(
                        GoAuthMethod(
                            authType = method.optString("authType"),
                            loginDomain = method.optString("loginDomain"),
                            authName = method.optString("authName"),
                        ),
                    )
                }
            }
        }

    private fun JSONArray?.toStrings(): List<String> =
        buildList {
            this@toStrings?.let { values ->
                for (index in 0 until values.length()) {
                    values.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }

    private companion object {
        const val ZJU_ATRUST_SERVER = "vpn.zju.edu.cn"
        const val ZJU_ATRUST_PORT = 443
    }
}

data class GoBridgeEvent(
    val type: String,
    val upstreamCommit: String,
    val message: String,
) {
    val displayText: String
        get() = "${message} (upstream ${upstreamCommit.take(12)})"
}

data class GoTestVpnEvent(
    val state: String,
    val code: String,
    val message: String,
    val packetsFromTun: Long = 0,
    val packetsToTun: Long = 0,
    val bytesFromTun: Long = 0,
    val bytesToTun: Long = 0,
)

data class GoAuthMethod(
    val authType: String,
    val loginDomain: String,
    val authName: String,
)

data class GoAuthEvent(
    val type: String,
    val state: String,
    val code: String,
    val message: String,
    val authMethods: List<GoAuthMethod> = emptyList(),
    val phoneNumbers: List<String> = emptyList(),
    val captchaWidth: Int = 0,
    val captchaHeight: Int = 0,
    val username: String = "",
)
