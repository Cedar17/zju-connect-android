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

    fun prepareRealVpn(): GoVpnPrepared = parseVpnPrepared(Core.prepareRealVpn())

    fun startRealVpn(
        tunFd: Long,
        socketProtector: SocketProtector,
        onEvent: (GoVpnEvent) -> Unit,
    ) {
        Core.startRealVpn(
            tunFd,
            socketProtector,
            object : BridgeListener {
                override fun onEvent(eventJson: String) {
                    onEvent(parseVpnEvent(eventJson))
                }
            },
        )
    }

    fun stopRealVpn() {
        Core.stopRealVpn()
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

    fun resumeAuthentication(
        snapshot: ByteArray,
        onEvent: (GoAuthEvent) -> Unit,
    ): GoAuthEvent = parseAuthEvent(Core.resumeAuthentication(snapshot, authListener(onEvent)))

    fun exportAuthenticatedSession(): ByteArray = Core.exportAuthenticatedSession()

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

    private fun parseVpnPrepared(eventJson: String): GoVpnPrepared = runCatching {
        val event = JSONObject(eventJson)
        GoVpnPrepared(
            state = event.optString("state", "unknown"),
            code = event.optString("code", ""),
            message = event.optString("message", "Real VPN response received"),
            stage = event.optString("stage", ""),
            cause = event.optString("cause", ""),
            address = event.optString("address", ""),
            mtu = event.optInt("mtu", 1400),
            routes = event.optJSONArray("routes").toVpnRoutes(),
        )
    }.getOrElse {
        GoVpnPrepared(
            state = "error",
            code = "invalidEvent",
            message = "Go bridge returned an invalid real VPN configuration",
        )
    }

    internal fun parseVpnEvent(eventJson: String): GoVpnEvent = runCatching {
        val event = JSONObject(eventJson)
        GoVpnEvent(
            state = event.optString("state", "unknown"),
            code = event.optString("code", ""),
            message = event.optString("message", "Real VPN event received"),
            stage = event.optString("stage", ""),
            cause = event.optString("cause", ""),
            diagnostics = event.optJSONObject("diagnostics").toVpnDiagnostics(),
            packet = event.optJSONObject("packet").toVpnPacketMetadata(),
        )
    }.getOrElse {
        GoVpnEvent(
            state = "error",
            code = "invalidEvent",
            message = "Go bridge returned an invalid real VPN event",
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

    private fun JSONArray?.toVpnRoutes(): List<GoVpnRoute> =
        buildList {
            this@toVpnRoutes?.let { routes ->
                for (index in 0 until routes.length()) {
                    val route = routes.optJSONObject(index) ?: continue
                    val address = route.optString("address").trim()
                    val prefixLength = route.optInt("prefixLength", -1)
                    if (address.isNotBlank() && prefixLength in 0..32) {
                        add(GoVpnRoute(address, prefixLength))
                    }
                }
            }
        }

    private fun JSONObject?.toVpnDiagnostics(): GoVpnDiagnostics? =
        this?.let { diagnostics ->
            GoVpnDiagnostics(
                tunReadPackets = diagnostics.optLong("tunReadPackets", 0),
                tunReadBytes = diagnostics.optLong("tunReadBytes", 0),
                forwardablePackets = diagnostics.optLong("forwardablePackets", 0),
                filteredPackets = diagnostics.optLong("filteredPackets", 0),
                l3WriteAttempts = diagnostics.optLong("l3WriteAttempts", 0),
                l3WriteSuccesses = diagnostics.optLong("l3WriteSuccesses", 0),
                resourceDrops = diagnostics.optLong("resourceDrops", 0),
                l3ReadPackets = diagnostics.optLong("l3ReadPackets", 0),
                l3ReadBytes = diagnostics.optLong("l3ReadBytes", 0),
                l3InvalidPackets = diagnostics.optLong("l3InvalidPackets", 0),
                tunWriteAttempts = diagnostics.optLong("tunWriteAttempts", 0),
                tunWriteSuccesses = diagnostics.optLong("tunWriteSuccesses", 0),
                tunWriteBytes = diagnostics.optLong("tunWriteBytes", 0),
            )
        }

    private fun JSONObject?.toVpnPacketMetadata(): GoVpnPacketMetadata? =
        this?.let { packet ->
            GoVpnPacketMetadata(
                sequence = packet.optLong("sequence", 0),
                direction = packet.optString("direction", ""),
                ipVersion = packet.optInt("ipVersion", 0),
                protocol = packet.optString("protocol", "unknown"),
                sourceIp = packet.optString("sourceIp", ""),
                destinationIp = packet.optString("destinationIp", ""),
                sourcePort = packet.optInt("sourcePort", 0),
                destinationPort = packet.optInt("destinationPort", 0),
                length = packet.optInt("length", 0),
                dataLength = packet.optInt("dataLength", 0),
                tcpFlags = packet.optInt("tcpFlags", 0),
                tcpSequence = packet.optLong("tcpSequence", 0),
                tcpAcknowledgment = packet.optLong("tcpAcknowledgment", 0),
                tcpWindow = packet.optInt("tcpWindow", 0),
                ipChecksum = packet.optString("ipChecksum", ""),
                transportChecksum = packet.optString("transportChecksum", ""),
                valid = packet.optBoolean("valid", false),
                truncated = packet.optBoolean("truncated", false),
            )
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

data class GoVpnRoute(
    val address: String,
    val prefixLength: Int,
)

data class GoVpnPrepared(
    val state: String,
    val code: String,
    val message: String,
    val stage: String = "",
    val cause: String = "",
    val address: String = "",
    val mtu: Int = 1400,
    val routes: List<GoVpnRoute> = emptyList(),
)

data class GoVpnEvent(
    val state: String,
    val code: String,
    val message: String,
    val stage: String = "",
    val cause: String = "",
    val diagnostics: GoVpnDiagnostics? = null,
    val packet: GoVpnPacketMetadata? = null,
)

data class GoVpnDiagnostics(
    val tunReadPackets: Long = 0,
    val tunReadBytes: Long = 0,
    val forwardablePackets: Long = 0,
    val filteredPackets: Long = 0,
    val l3WriteAttempts: Long = 0,
    val l3WriteSuccesses: Long = 0,
    val resourceDrops: Long = 0,
    val l3ReadPackets: Long = 0,
    val l3ReadBytes: Long = 0,
    val l3InvalidPackets: Long = 0,
    val tunWriteAttempts: Long = 0,
    val tunWriteSuccesses: Long = 0,
    val tunWriteBytes: Long = 0,
)

data class GoVpnPacketMetadata(
    val sequence: Long = 0,
    val direction: String = "",
    val ipVersion: Int = 0,
    val protocol: String = "unknown",
    val sourceIp: String = "",
    val destinationIp: String = "",
    val sourcePort: Int = 0,
    val destinationPort: Int = 0,
    val length: Int = 0,
    val dataLength: Int = 0,
    val tcpFlags: Int = 0,
    val tcpSequence: Long = 0,
    val tcpAcknowledgment: Long = 0,
    val tcpWindow: Int = 0,
    val ipChecksum: String = "",
    val transportChecksum: String = "",
    val valid: Boolean = false,
    val truncated: Boolean = false,
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
