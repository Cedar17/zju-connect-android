package io.github.cedar17.zjuconnect

import org.junit.Assert.assertEquals
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
    fun progressErrorAndChallengeTextRemainExplicit() {
        assertTrue(
            connectionPresentation(
                ConnectionUiState(phase = ConnectionPhase.RESTORING_SESSION),
            ).showsProgress,
        )
        assertTrue(
            connectionPresentation(
                ConnectionUiState(phase = ConnectionPhase.DISCONNECTING),
            ).showsProgress,
        )
        assertTrue(
            connectionPresentation(
                ConnectionUiState(phase = ConnectionPhase.PREPARING_VPN_PERMISSION),
            ).showsProgress,
        )
        assertEquals(
            UiText.Resource(R.string.error_auth_timeout),
            connectionPresentation(
                ConnectionUiState(
                    phase = ConnectionPhase.ERROR,
                    internalCode = "authNetworkTimeout",
                ),
            ).supportingText,
        )
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
            UiText.Resource(R.string.connection_status_awaiting_totp),
            tokenChallengeUiText("auth/totp"),
        )
    }
}
