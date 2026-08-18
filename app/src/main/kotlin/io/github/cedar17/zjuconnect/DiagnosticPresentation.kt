package io.github.cedar17.zjuconnect

internal fun diagnosticCategoryText(category: String): UiText = UiText.Resource(
    id = when (category) {
        "connection" -> R.string.diagnostic_category_connection
        "vpn" -> R.string.diagnostic_category_vpn
        "service" -> R.string.diagnostic_category_service
        "authRecovery" -> R.string.diagnostic_category_auth_recovery
        else -> R.string.diagnostic_unknown_category
    },
)

internal fun diagnosticStateText(event: RedactedDiagnosticEvent): UiText = UiText.Resource(
    id = when (event.category) {
        "connection" -> when (event.state) {
            "disconnected" -> R.string.diagnostic_connection_disconnected
            "restoring_session" -> R.string.diagnostic_connection_restoring_session
            "fetching_auth_methods" -> R.string.diagnostic_connection_fetching_auth_methods
            "authenticating" -> R.string.diagnostic_connection_authenticating
            "awaiting_credentials" -> R.string.diagnostic_connection_awaiting_credentials
            "awaiting_phone" -> R.string.diagnostic_connection_awaiting_phone
            "awaiting_sms" -> R.string.diagnostic_connection_awaiting_sms
            "awaiting_token" -> R.string.diagnostic_connection_awaiting_token
            "awaiting_captcha" -> R.string.diagnostic_connection_awaiting_captcha
            "preparing_vpn_permission" -> R.string.diagnostic_connection_preparing_vpn_permission
            "establishing_vpn" -> R.string.diagnostic_connection_establishing_vpn
            "recovering_vpn" -> R.string.diagnostic_connection_recovering_vpn
            "connected" -> R.string.diagnostic_connection_connected
            "disconnecting" -> R.string.diagnostic_connection_disconnecting
            "error" -> R.string.diagnostic_connection_error
            else -> R.string.diagnostic_unknown_state
        }
        "vpn" -> when (event.state) {
            "idle" -> R.string.diagnostic_vpn_idle
            "preparing" -> R.string.diagnostic_vpn_preparing
            "attaching" -> R.string.diagnostic_vpn_attaching
            "starting" -> R.string.diagnostic_vpn_starting
            "active" -> R.string.diagnostic_vpn_active
            "stopping" -> R.string.diagnostic_vpn_stopping
            "stopped" -> R.string.diagnostic_vpn_stopped
            "error" -> R.string.diagnostic_vpn_error
            "diagnostic" -> R.string.diagnostic_vpn_data_plane
            "recovering" -> R.string.diagnostic_vpn_recovering
            "waitingForNetwork" -> R.string.diagnostic_vpn_waiting_network
            "waitingForAuthentication" -> R.string.diagnostic_vpn_waiting_authentication
            "alwaysOnDisconnectBlocked" -> R.string.diagnostic_vpn_always_on
            else -> R.string.diagnostic_unknown_state
        }
        "service" -> when (event.state) {
            "idle" -> R.string.diagnostic_service_idle
            "preparing" -> R.string.diagnostic_service_preparing
            "attaching" -> R.string.diagnostic_service_attaching
            "starting" -> R.string.diagnostic_service_starting
            "active" -> R.string.diagnostic_service_active
            "stopping" -> R.string.diagnostic_service_stopping
            "stopped" -> R.string.diagnostic_service_stopped
            "error" -> R.string.diagnostic_service_error
            "diagnostic" -> R.string.diagnostic_service_data
            "recovering" -> R.string.diagnostic_service_recovering
            "waitingForNetwork" -> R.string.diagnostic_service_waiting_network
            "waitingForAuthentication" -> R.string.diagnostic_service_waiting_authentication
            "alwaysOnDisconnectBlocked" -> R.string.diagnostic_service_always_on
            "sessionRestore" -> R.string.diagnostic_service_session_restore
            else -> R.string.diagnostic_unknown_state
        }
        "authRecovery" -> when (event.state) {
            "reusable_result" -> R.string.diagnostic_auth_reusable_result
            "persisted_session" -> R.string.diagnostic_auth_persisted_session
            "persisted_session_authenticated" -> R.string.diagnostic_auth_persisted_session_authenticated
            "persisted_session_stale" -> R.string.diagnostic_auth_persisted_session_stale
            "saved_credentials" -> R.string.diagnostic_auth_saved_credentials
            "server_challenge" -> R.string.diagnostic_auth_server_challenge
            else -> R.string.diagnostic_unknown_state
        }
        else -> R.string.diagnostic_unknown_state
    },
)

internal fun diagnosticErrorText(code: String): UiText = when (code) {
    "", "unknown" -> UiText.Resource(R.string.diagnostic_unknown_error)
    "l3Reconnecting" -> UiText.Resource(R.string.diagnostic_l3_reconnecting)
    else -> connectionErrorText(code)
}
