package io.github.cedar17.zjuconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPresentationTest {
    @Test
    fun primaryActionsKeepStableAndInteractiveMappings() {
        assertEquals(
            UiText.Resource(R.string.action_connect),
            connectionPresentation(ConnectionUiState()).primaryAction,
        )
        assertEquals(
            UiText.Resource(R.string.action_login_and_connect),
            connectionPresentation(ConnectionUiState(phase = ConnectionPhase.AWAITING_CREDENTIALS)).primaryAction,
        )
        assertEquals(
            UiText.Resource(R.string.action_disconnect),
            connectionPresentation(ConnectionUiState(phase = ConnectionPhase.CONNECTED)).primaryAction,
        )
        assertEquals(
            UiText.Resource(R.string.action_retry),
            connectionPresentation(ConnectionUiState(phase = ConnectionPhase.ERROR)).primaryAction,
        )
    }

    @Test
    fun phaseGroupsUseStableSupportingText() {
        listOf(
            ConnectionPhase.RESTORING_SESSION,
            ConnectionPhase.FETCHING_AUTH_METHODS,
            ConnectionPhase.AUTHENTICATING,
        ).forEach { phase ->
            assertEquals(
                UiText.Resource(R.string.connection_status_signing_in),
                connectionPresentation(ConnectionUiState(phase = phase)).supportingText,
            )
        }
        listOf(
            ConnectionPhase.PREPARING_VPN_PERMISSION,
            ConnectionPhase.ESTABLISHING_VPN,
        ).forEach { phase ->
            assertEquals(
                UiText.Resource(R.string.connection_status_establishing_vpn),
                connectionPresentation(ConnectionUiState(phase = phase)).supportingText,
            )
        }
        assertEquals(
            UiText.Resource(R.string.connection_status_recovering_connection),
            connectionPresentation(ConnectionUiState(phase = ConnectionPhase.RECOVERING_VPN)).supportingText,
        )
        assertEquals(
            UiText.Resource(R.string.connection_status_disconnecting_vpn),
            connectionPresentation(ConnectionUiState(phase = ConnectionPhase.DISCONNECTING)).supportingText,
        )
    }

    @Test
    fun inputAndConnectedStatesUseStructuredSupportingText() {
        mapOf(
            ConnectionPhase.AWAITING_CREDENTIALS to R.string.connection_status_awaiting_credentials,
            ConnectionPhase.AWAITING_PHONE to R.string.connection_status_awaiting_phone,
            ConnectionPhase.AWAITING_SMS to R.string.connection_status_awaiting_sms,
            ConnectionPhase.AWAITING_CAPTCHA to R.string.connection_status_awaiting_captcha,
        ).forEach { (phase, resource) ->
            assertEquals(
                UiText.Resource(resource),
                connectionPresentation(ConnectionUiState(phase = phase)).supportingText,
            )
        }
        assertEquals(
            UiText.Resource(R.string.connection_account, listOf("student")),
            connectionPresentation(
                ConnectionUiState(
                    phase = ConnectionPhase.CONNECTED,
                    rememberedUsername = "student",
                ),
            ).supportingText,
        )
        assertEquals(
            UiText.Resource(R.string.always_on_disconnect_guidance),
            connectionPresentation(
                ConnectionUiState(
                    phase = ConnectionPhase.CONNECTED,
                    internalCode = ALWAYS_ON_DISCONNECT_BLOCKED_CODE,
                ),
            ).supportingText,
        )
    }

    @Test
    fun errorsMapCodesToLocalizedResourcesWithoutExposingCodes() {
        mapOf(
            "vpnPermissionDenied" to R.string.error_vpn_permission_denied,
            "authDnsFailure" to R.string.error_auth_dns,
            "authNetworkFailure" to R.string.error_auth_network,
            "authNetworkTimeout" to R.string.error_auth_timeout,
            "authProtocolFailure" to R.string.error_auth_protocol,
            "authServerFailure" to R.string.error_auth_server,
            "vpnSessionInvalid" to R.string.error_session_invalid,
            "vpnConfigurationUnavailable" to R.string.error_vpn_configuration,
            "certificateRejected" to R.string.error_certificate_rejected,
            "unsupportedAuthMethod" to R.string.error_unsupported_auth,
            "invalidInput" to R.string.error_authentication_failed,
            "authenticationFailed" to R.string.error_authentication_failed,
            "sessionStoreUnavailable" to R.string.error_session_store,
            "credentialStoreUnavailable" to R.string.error_credential_store,
            "deviceIdentityUnavailable" to R.string.error_device_identity,
            "accountSwitchClearFailed" to R.string.error_account_switch_clear,
            "sessionRestoreUnavailable" to R.string.error_session_restore,
            "alwaysOnAuthenticationRequired" to R.string.error_always_on_authentication,
            "alwaysOnDisconnectBlocked" to R.string.error_always_on_disconnect,
            "authInfoUnavailable" to R.string.error_auth_info,
            "initializationFailed" to R.string.error_auth_info,
            "vpnRevoked" to R.string.error_vpn_revoked,
            "vpnStopDispatchFailed" to R.string.error_vpn_stop_dispatch,
            "vpnStartDispatchFailed" to R.string.error_vpn_start_dispatch,
            "networkMonitorUnavailable" to R.string.error_network_monitor,
            "vpnSetupFailed" to R.string.error_vpn_setup,
            "vpnAddressUnavailable" to R.string.error_vpn_setup,
            "vpnRoutesUnavailable" to R.string.error_vpn_setup,
            "tunEstablishFailed" to R.string.error_tun_establish,
            "tunEstablishTimeout" to R.string.error_tun_establish,
            "tunInitializationFailed" to R.string.error_tun_establish,
            "vpnTunReadFailed" to R.string.error_vpn_interrupted,
            "vpnTunWriteFailed" to R.string.error_vpn_interrupted,
            "vpnServerReadFailed" to R.string.error_vpn_interrupted,
            "vpnServerWriteFailed" to R.string.error_vpn_interrupted,
            "stopTimeout" to R.string.error_vpn_interrupted,
            "unexpectedInternalCode" to R.string.error_generic,
        ).forEach { (code, resource) ->
            val presentation = connectionPresentation(
                ConnectionUiState(
                    phase = ConnectionPhase.ERROR,
                    internalCode = code,
                ),
            )
            assertEquals(UiText.Resource(resource), presentation.supportingText)
            assertTrue(presentation.isError)
        }
    }

    @Test
    fun tokenChallengesUseServerSpecificResources() {
        mapOf(
            "auth/totp" to R.string.connection_status_awaiting_totp,
            "auth/radius" to R.string.connection_status_awaiting_radius,
            "auth/challenge" to R.string.connection_status_awaiting_challenge,
            "auth/unknown" to R.string.connection_status_awaiting_token,
        ).forEach { (challengeKind, resource) ->
            assertEquals(UiText.Resource(resource), tokenChallengeUiText(challengeKind))
        }
    }

    @Test
    fun actionsAndProgressFollowPhaseBoundaries() {
        assertFalse(
            connectionPresentation(ConnectionUiState(phase = ConnectionPhase.AUTHENTICATING))
                .primaryActionEnabled,
        )
        assertTrue(
            connectionPresentation(ConnectionUiState(phase = ConnectionPhase.RECOVERING_VPN))
                .primaryActionEnabled,
        )
        assertTrue(
            connectionPresentation(ConnectionUiState(phase = ConnectionPhase.RESTORING_SESSION))
                .showsProgress,
        )
        assertTrue(
            connectionPresentation(ConnectionUiState(phase = ConnectionPhase.PREPARING_VPN_PERMISSION))
                .showsProgress,
        )
        assertTrue(
            connectionPresentation(ConnectionUiState(phase = ConnectionPhase.DISCONNECTING))
                .showsProgress,
        )
        assertFalse(
            connectionPresentation(ConnectionUiState(phase = ConnectionPhase.CONNECTED))
                .showsProgress,
        )
    }
}
