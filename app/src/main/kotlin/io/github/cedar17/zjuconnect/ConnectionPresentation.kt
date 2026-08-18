package io.github.cedar17.zjuconnect

import androidx.annotation.StringRes

internal data class ConnectionPresentation(
    val title: UiText,
    val supportingText: UiText,
    val primaryAction: UiText,
    val primaryActionEnabled: Boolean,
    val showsProgress: Boolean,
    val isError: Boolean,
)

internal fun connectionPresentation(state: ConnectionUiState): ConnectionPresentation =
    ConnectionPresentation(
        title = connectionTitleText(state.phase),
        supportingText = connectionSupportingTextValue(state),
        primaryAction = primaryActionText(state.phase),
        primaryActionEnabled = isPrimaryActionEnabled(state),
        showsProgress = isConnectionProgress(state.phase),
        isError = state.phase == ConnectionPhase.ERROR,
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

private fun connectionSupportingTextValue(state: ConnectionUiState): UiText = when {
    state.phase == ConnectionPhase.CONNECTED &&
        state.statusMessage == "请先在系统 VPN 设置中关闭 Always-on" ->
        UiText.Resource(R.string.always_on_disconnect_guidance)

    state.phase == ConnectionPhase.DISCONNECTED || state.phase == ConnectionPhase.CONNECTED ->
        if (state.rememberedUsername.isBlank()) {
            UiText.Resource(R.string.connection_no_saved_account)
        } else {
            UiText.Resource(
                R.string.connection_account,
                listOf(state.rememberedUsername),
            )
        }

    state.phase == ConnectionPhase.ERROR -> connectionErrorText(state.internalCode)
    else -> statusMessageText(state.statusMessage)
}

/** Retained as a pure policy helper for existing tests and non-Compose callers. */
internal fun connectionSupportingText(state: ConnectionUiState): String =
    if (state.phase == ConnectionPhase.DISCONNECTED || state.phase == ConnectionPhase.CONNECTED) {
        if (state.rememberedUsername.isBlank()) {
            "尚未保存账号"
        } else {
            "账号：${state.rememberedUsername}"
        }
    } else {
        state.statusMessage
    }

private fun statusMessageText(message: String): UiText = UiText.Resource(
    id = statusMessageResource(message),
)

@StringRes
private fun statusMessageResource(message: String): Int = when (message) {
    "尚未连接" -> R.string.connection_status_disconnected
    "正在复用上次认证状态…" -> R.string.connection_status_reusing_auth
    "正在建立 VPN…" -> R.string.connection_status_establishing_vpn
    "正在检查已保存的登录状态…" -> R.string.connection_status_checking_saved_session
    "正在验证已保存的登录状态…" -> R.string.connection_status_verifying_saved_session
    "正在获取学校要求的登录方式…" -> R.string.connection_status_fetching_auth_methods
    "正在验证账号…" -> R.string.connection_status_verifying_account
    "正在发送短信验证码…" -> R.string.connection_status_sending_sms
    "正在验证短信验证码…" -> R.string.connection_status_verifying_sms
    "正在验证服务端要求的认证码…" -> R.string.connection_status_verifying_token
    "正在验证图形验证码…" -> R.string.connection_status_verifying_captcha
    "请输入服务端要求的手机号" -> R.string.connection_status_awaiting_phone
    "请输入收到的短信验证码" -> R.string.connection_status_awaiting_sms
    "请输入动态认证码" -> R.string.connection_status_awaiting_totp
    "请输入 RADIUS 认证码" -> R.string.connection_status_awaiting_radius
    "请输入服务端挑战码" -> R.string.connection_status_awaiting_challenge
    "请输入服务端要求的认证码" -> R.string.connection_status_awaiting_token
    "请按提示完成图形验证码" -> R.string.connection_status_awaiting_captcha
    "正在完成登录…" -> R.string.connection_status_finishing_login
    "保存的密码已失效，请重新输入" -> R.string.connection_status_saved_password_expired
    "正在使用已保存的凭据重新验证…" -> R.string.connection_status_verifying_saved_credentials
    "请输入浙大上网账号和密码" -> R.string.connection_status_awaiting_credentials
    "正在准备登录…" -> R.string.connection_status_preparing_login
    "正在检查系统 VPN 权限…" -> R.string.connection_status_checking_vpn_permission
    "正在恢复 VPN…" -> R.string.connection_status_recovering_vpn
    "正在等待可用网络…" -> R.string.connection_status_waiting_for_network
    "正在断开 VPN…" -> R.string.connection_status_disconnecting_vpn
    "正在切换账号…" -> R.string.connection_status_switching_account
    "请先在系统 VPN 设置中关闭 Always-on" -> R.string.always_on_disconnect_guidance
    else -> R.string.connection_status_generic
}

private fun connectionErrorText(code: String): UiText = UiText.Resource(
    id = connectionErrorResource(code),
)

@StringRes
private fun connectionErrorResource(code: String): Int = when (code) {
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
    "alwaysOnDisconnectBlocked" -> R.string.error_always_on_disconnect
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
}

private fun primaryActionText(phase: ConnectionPhase): UiText = UiText.Resource(
    id = when (phase) {
        ConnectionPhase.DISCONNECTED -> R.string.action_connect
        ConnectionPhase.ERROR -> R.string.action_retry
        ConnectionPhase.AWAITING_CREDENTIALS -> R.string.action_login_and_connect
        ConnectionPhase.AWAITING_PHONE -> R.string.action_send_code
        ConnectionPhase.AWAITING_SMS,
        ConnectionPhase.AWAITING_TOKEN -> R.string.action_verify_and_connect
        ConnectionPhase.AWAITING_CAPTCHA -> R.string.action_submit_and_continue
        ConnectionPhase.RECOVERING_VPN,
        ConnectionPhase.CONNECTED -> R.string.action_disconnect
        else -> R.string.action_connecting
    },
)

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

private fun isConnectionProgress(phase: ConnectionPhase): Boolean = phase in setOf(
    ConnectionPhase.RESTORING_SESSION,
    ConnectionPhase.FETCHING_AUTH_METHODS,
    ConnectionPhase.AUTHENTICATING,
    ConnectionPhase.PREPARING_VPN_PERMISSION,
    ConnectionPhase.ESTABLISHING_VPN,
    ConnectionPhase.DISCONNECTING,
)
