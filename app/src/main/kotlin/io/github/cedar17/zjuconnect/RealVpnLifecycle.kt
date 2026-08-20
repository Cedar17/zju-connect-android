package io.github.cedar17.zjuconnect

/**
 * Small, Android-free policy object for the real VPN service's terminal state.
 *
 * Its caller serializes access. Keeping this policy separate makes it possible
 * to verify that cleanup callbacks cannot replace the first real failure.
 */
internal class RealVpnLifecycle {
    private var sessionStarted = false
    private var userStopRequested = false
    private var terminalFailure: RealVpnFailure? = null

    fun beginSession(): Boolean {
        if (sessionStarted) {
            return false
        }
        sessionStarted = true
        userStopRequested = false
        terminalFailure = null
        return true
    }

    fun requestUserStop() {
        userStopRequested = true
    }

    fun recordFailure(code: String, message: String): RealVpnFailure? {
        terminalFailure?.let { return it }
        if (userStopRequested) {
            return null
        }
        return RealVpnFailure(
            code = code.ifBlank { "vpnDataPlaneFailed" },
            message = message.ifBlank { "The real aTrust VPN stopped unexpectedly" },
        ).also { terminalFailure = it }
    }

    fun recordUnexpectedDestruction(): RealVpnFailure? {
        if (!sessionStarted || userStopRequested || terminalFailure != null) {
            return null
        }
        return recordFailure(
            code = "serviceDestroyed",
            message = "Android destroyed the VPN service before it completed",
        )
    }

    fun recordRevocation(): RealVpnFailure? = recordFailure(
        code = "vpnRevoked",
        message = "Android revoked the real VPN connection",
    )

    fun beginCleanup(): Boolean {
        if (!sessionStarted) {
            return false
        }
        sessionStarted = false
        return true
    }

    fun hasActiveSession(): Boolean = sessionStarted

    fun acceptsProgress(): Boolean = terminalFailure == null && !userStopRequested

    fun terminalOutcome(): RealVpnTerminalOutcome? = when {
        terminalFailure != null -> RealVpnTerminalOutcome.Error(terminalFailure!!)
        userStopRequested -> RealVpnTerminalOutcome.Stopped
        else -> null
    }
}

internal data class RealVpnFailure(
    val code: String,
    val message: String,
)

internal sealed interface RealVpnTerminalOutcome {
    data class Error(val failure: RealVpnFailure) : RealVpnTerminalOutcome

    data object Stopped : RealVpnTerminalOutcome
}
