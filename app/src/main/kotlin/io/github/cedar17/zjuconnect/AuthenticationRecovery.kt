package io.github.cedar17.zjuconnect

/** Stable, secret-free labels for the authentication recovery ladder. */
internal enum class AuthenticationRecoverySource(
    val diagnosticState: String,
) {
    REUSABLE_RESULT("reusable_result"),
    PERSISTED_SESSION("persisted_session"),
    PERSISTED_SESSION_AUTHENTICATED("persisted_session_authenticated"),
    PERSISTED_SESSION_STALE("persisted_session_stale"),
    SAVED_CREDENTIALS("saved_credentials"),
    SERVER_CHALLENGE("server_challenge"),
}

/** Stable outcomes recorded without including authentication material. */
internal enum class AuthenticationRecoveryOutcome(
    val diagnosticCode: String,
) {
    SELECTED("selected"),
    REAUTHENTICATING("reauthenticating"),
    SUBMITTED("submitted"),
    AUTHENTICATED("authenticated"),
    WAITING_FOR_USER("waitingForUser"),
    INVALIDATED("invalidated"),
    REJECTED("rejected"),
    UNAVAILABLE("unavailable"),
    FAILED("failed"),
}

/** Stable, secret-free causes attached to explicit authentication invalidation diagnostics. */
internal enum class AuthenticationInvalidationCause(
    val diagnosticCause: String,
) {
    ACCOUNT_SWITCH("accountSwitch"),
    INVALID_STORED_SESSION("invalidStoredSession"),
    REUSABLE_RESULT_REJECTED("reusableResultRejected"),
    CREDENTIALS_REJECTED("credentialsRejected"),
}

internal fun isDefinitivelyInvalidStoredSession(
    eventType: String,
    code: String,
): Boolean = eventType == "sessionInvalid" || code in setOf("invalidSession", "sessionInvalid")

internal fun shouldFallbackFromReusableAuthentication(code: String, cause: String): Boolean =
    code == "vpnSessionInvalid" || cause in setOf("authentication", "serverRejected")

internal fun isCredentialExplicitlyRejected(code: String): Boolean = code == "credentialsRejected"
