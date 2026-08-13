package io.github.cedar17.zjuconnect

internal enum class RealVpnBridgeEventKind {
    DIAGNOSTIC,
    TERMINAL_ERROR,
    L3_RECOVERING,
    L3_RECOVERED,
    PROGRESS,
}

internal fun classifyRealVpnBridgeEvent(event: GoVpnEvent): RealVpnBridgeEventKind = when {
    event.state == "diagnostic" -> RealVpnBridgeEventKind.DIAGNOSTIC
    event.state == "error" -> RealVpnBridgeEventKind.TERMINAL_ERROR
    event.state == "recovering" && event.stage == "dataplane.l3.reconnect" ->
        RealVpnBridgeEventKind.L3_RECOVERING
    event.state == "active" && event.stage == "dataplane.l3.recovered" ->
        RealVpnBridgeEventKind.L3_RECOVERED
    else -> RealVpnBridgeEventKind.PROGRESS
}
