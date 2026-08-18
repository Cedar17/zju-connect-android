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

/**
 * Boundaries that can dispose one or more authentication stores. A ViewModel
 * teardown deliberately has an empty disposition: it is not a user logout.
 */
internal enum class AuthenticationStateBoundary(
    val diagnosticCause: String,
) {
    VIEW_MODEL_TEARDOWN("viewModelTeardown"),
    ACCOUNT_SWITCH("accountSwitch"),
    INVALID_STORED_SESSION("invalidStoredSession"),
    REUSABLE_RESULT_REJECTED("reusableResultRejected"),
    CREDENTIALS_REJECTED("credentialsRejected"),
}

internal data class AuthenticationStateDisposition(
    val clearInProcessResult: Boolean = false,
    val clearStoredSession: Boolean = false,
    val clearSavedCredential: Boolean = false,
    val clearRememberedAccount: Boolean = false,
)

internal fun authenticationStateDisposition(
    boundary: AuthenticationStateBoundary,
): AuthenticationStateDisposition = when (boundary) {
    AuthenticationStateBoundary.VIEW_MODEL_TEARDOWN -> AuthenticationStateDisposition()
    AuthenticationStateBoundary.ACCOUNT_SWITCH -> AuthenticationStateDisposition(
        clearInProcessResult = true,
        clearStoredSession = true,
        clearSavedCredential = true,
        clearRememberedAccount = true,
    )
    AuthenticationStateBoundary.INVALID_STORED_SESSION -> AuthenticationStateDisposition(
        clearStoredSession = true,
    )
    AuthenticationStateBoundary.REUSABLE_RESULT_REJECTED -> AuthenticationStateDisposition(
        clearInProcessResult = true,
    )
    AuthenticationStateBoundary.CREDENTIALS_REJECTED -> AuthenticationStateDisposition(
        clearSavedCredential = true,
    )
}

internal fun storedSessionInvalidationBoundary(
    eventType: String,
    code: String,
): AuthenticationStateBoundary? =
    if (eventType == "sessionInvalid" || code in setOf("invalidSession", "sessionInvalid")) {
        AuthenticationStateBoundary.INVALID_STORED_SESSION
    } else {
        null
    }

internal fun credentialInvalidationBoundary(code: String): AuthenticationStateBoundary? =
    if (code == "credentialsRejected") {
        AuthenticationStateBoundary.CREDENTIALS_REJECTED
    } else {
        null
    }
