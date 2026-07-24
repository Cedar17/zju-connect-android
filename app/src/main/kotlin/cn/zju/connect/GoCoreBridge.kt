package cn.zju.connect

import cn.zju.connect.gocore.core.BridgeListener
import cn.zju.connect.gocore.core.Core
import cn.zju.connect.gocore.core.SocketProtector
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
