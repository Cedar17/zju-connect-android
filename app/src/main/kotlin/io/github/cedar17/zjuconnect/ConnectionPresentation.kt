package io.github.cedar17.zjuconnect

internal const val ALWAYS_ON_DISCONNECT_BLOCKED_CODE = "alwaysOnDisconnectBlocked"

internal data class ConnectionPresentation(
    val title: UiText,
    val supportingText: UiText,
    val primaryAction: UiText?,
    val primaryActionEnabled: Boolean,
)

internal fun connectionPresentation(state: ConnectionUiState): ConnectionPresentation =
    ConnectionPresentation(
        title = connectionTitleText(state.phase),
        supportingText = supportingTextFor(state),
        primaryAction = primaryActionText(state.phase),
        primaryActionEnabled = isPrimaryActionEnabled(state),
    )

private fun connectionTitleText(phase: ConnectionPhase): UiText = UiText.Resource(
    id = when (phase) {
        ConnectionPhase.CONNECTED -> R.string.connection_title_connected
        ConnectionPhase.RECOVERING_VPN -> R.string.connection_title_recovering
        ConnectionPhase.ERROR -> R.string.connection_title_error
        ConnectionPhase.DISCONNECTED -> R.string.connection_title_disconnected
        ConnectionPhase.DISCONNECTING -> R.string.connection_title_disconnecting
        else -> R.string.connection_title_connecting
    },
)

private fun supportingTextFor(state: ConnectionUiState): UiText = when (state.phase) {
    ConnectionPhase.DISCONNECTED,
    ConnectionPhase.CONNECTED,
    -> if (
        state.phase == ConnectionPhase.CONNECTED &&
        state.internalCode == ALWAYS_ON_DISCONNECT_BLOCKED_CODE
    ) {
        UiText.Resource(R.string.always_on_disconnect_guidance)
    } else {
        rememberedAccountText(state.rememberedUsername)
    }

    ConnectionPhase.ERROR -> connectionErrorText(state.internalCode)
    ConnectionPhase.RESTORING_SESSION,
    ConnectionPhase.FETCHING_AUTH_METHODS,
    ConnectionPhase.AUTHENTICATING,
    -> UiText.Resource(R.string.connection_status_signing_in)

    ConnectionPhase.AWAITING_CREDENTIALS ->
        UiText.Resource(R.string.connection_status_awaiting_credentials)
    ConnectionPhase.AWAITING_PHONE -> UiText.Resource(R.string.connection_status_awaiting_phone)
    ConnectionPhase.AWAITING_SMS -> UiText.Resource(R.string.connection_status_awaiting_sms)
    ConnectionPhase.AWAITING_TOKEN -> tokenChallengeUiText(state.challengeKind)
    ConnectionPhase.AWAITING_CAPTCHA -> UiText.Resource(R.string.connection_status_awaiting_captcha)
    ConnectionPhase.PREPARING_VPN_PERMISSION,
    ConnectionPhase.ESTABLISHING_VPN,
    -> UiText.Resource(R.string.connection_status_establishing_vpn)
    ConnectionPhase.RECOVERING_VPN -> UiText.Resource(R.string.connection_status_recovering_connection)
    ConnectionPhase.DISCONNECTING -> UiText.Resource(R.string.connection_status_disconnecting_vpn)
}

private fun rememberedAccountText(username: String): UiText = if (username.isBlank()) {
    UiText.Resource(R.string.connection_no_saved_account)
} else {
    UiText.Resource(
        R.string.connection_account,
        listOf(username),
    )
}

internal fun connectionErrorText(code: String): UiText = UiText.Resource(
    id = when (code) {
    "vpnPermissionDenied" -> R.string.error_vpn_permission_denied
    "authDnsFailure" -> R.string.error_auth_dns
    "authNetworkFailure" -> R.string.error_auth_network
    "authNetworkTimeout" -> R.string.error_auth_timeout
    "authProtocolFailure" -> R.string.error_auth_protocol
    "authServerFailure" -> R.string.error_auth_server
    "vpnSessionInvalid" -> R.string.error_session_invalid
    "vpnConfigurationUnavailable" -> R.string.error_vpn_configuration
    "certificateRejected" -> R.string.error_certificate_rejected
    "unsupportedAuthMethod" -> R.string.error_unsupported_auth
    "invalidInput", "authenticationFailed" -> R.string.error_authentication_failed
    "sessionStoreUnavailable" -> R.string.error_session_store
    "credentialStoreUnavailable" -> R.string.error_credential_store
    "deviceIdentityUnavailable" -> R.string.error_device_identity
    "accountSwitchClearFailed" -> R.string.error_account_switch_clear
    "sessionRestoreUnavailable" -> R.string.error_session_restore
    "alwaysOnAuthenticationRequired" -> R.string.error_always_on_authentication
    ALWAYS_ON_DISCONNECT_BLOCKED_CODE -> R.string.error_always_on_disconnect
    "authInfoUnavailable", "initializationFailed" -> R.string.error_auth_info
    "vpnRevoked" -> R.string.error_vpn_revoked
    "vpnStopDispatchFailed" -> R.string.error_vpn_stop_dispatch
    "vpnStartDispatchFailed" -> R.string.error_vpn_start_dispatch
    "networkMonitorUnavailable" -> R.string.error_network_monitor
    "vpnSetupFailed", "vpnAddressUnavailable", "vpnRoutesUnavailable" -> R.string.error_vpn_setup
    "tunEstablishFailed", "tunEstablishTimeout", "tunInitializationFailed" -> R.string.error_tun_establish
    "vpnTunReadFailed", "vpnTunWriteFailed", "vpnServerReadFailed", "vpnServerWriteFailed", "stopTimeout" ->
        R.string.error_vpn_interrupted
    else -> R.string.error_generic
    },
)

private fun primaryActionText(phase: ConnectionPhase): UiText? = when (phase) {
    ConnectionPhase.RESTORING_SESSION,
    ConnectionPhase.FETCHING_AUTH_METHODS,
    ConnectionPhase.AUTHENTICATING,
    ConnectionPhase.PREPARING_VPN_PERMISSION,
    ConnectionPhase.ESTABLISHING_VPN,
    ConnectionPhase.DISCONNECTING,
    -> null
    ConnectionPhase.DISCONNECTED -> UiText.Resource(R.string.action_connect)
    ConnectionPhase.ERROR -> UiText.Resource(R.string.action_retry)
    ConnectionPhase.AWAITING_CREDENTIALS -> UiText.Resource(R.string.action_login_and_connect)
    ConnectionPhase.AWAITING_PHONE -> UiText.Resource(R.string.action_send_code)
    ConnectionPhase.AWAITING_SMS,
    ConnectionPhase.AWAITING_TOKEN,
    -> UiText.Resource(R.string.action_verify_and_connect)
    ConnectionPhase.AWAITING_CAPTCHA -> UiText.Resource(R.string.action_submit_and_continue)
    ConnectionPhase.RECOVERING_VPN,
    ConnectionPhase.CONNECTED,
    -> UiText.Resource(R.string.action_disconnect)
}

internal fun tokenChallengeUiText(challengeKind: String): UiText = UiText.Resource(
    id = when (challengeKind) {
        "auth/totp" -> R.string.connection_status_awaiting_totp
        "auth/radius" -> R.string.connection_status_awaiting_radius
        "auth/challenge" -> R.string.connection_status_awaiting_challenge
        else -> R.string.connection_status_awaiting_token
    },
)

private fun isPrimaryActionEnabled(state: ConnectionUiState): Boolean = when (state.phase) {
    ConnectionPhase.DISCONNECTED,
    ConnectionPhase.ERROR,
    ConnectionPhase.RECOVERING_VPN,
    ConnectionPhase.CONNECTED -> true
    ConnectionPhase.AWAITING_CREDENTIALS -> state.username.isNotBlank() && state.password.isNotBlank()
    ConnectionPhase.AWAITING_PHONE -> state.phone.isNotBlank()
    ConnectionPhase.AWAITING_SMS -> state.smsCode.isNotBlank()
    ConnectionPhase.AWAITING_TOKEN -> state.token.isNotBlank()
    ConnectionPhase.AWAITING_CAPTCHA -> state.captchaPoints.isNotEmpty()
    else -> false
}
