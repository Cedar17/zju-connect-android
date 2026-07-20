package cn.zju.connect

import cn.zju.connect.gocore.core.BridgeListener
import cn.zju.connect.gocore.core.Core
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
}

data class GoBridgeEvent(
    val type: String,
    val upstreamCommit: String,
    val message: String,
) {
    val displayText: String
        get() = "${message} (upstream ${upstreamCommit.take(12)})"
}
