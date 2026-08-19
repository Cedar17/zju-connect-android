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
            "authNetworkTimeout" to R.string.error_auth_timeout,
            "vpnSessionInvalid" to R.string.error_session_invalid,
            "alwaysOnDisconnectBlocked" to R.string.error_always_on_disconnect,
            "vpnTunWriteFailed" to R.string.error_vpn_interrupted,
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
