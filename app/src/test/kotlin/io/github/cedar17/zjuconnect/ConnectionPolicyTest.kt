package io.github.cedar17.zjuconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPolicyTest {
    @Test
    fun recoveryAndConnectedPhasesUseTheDisconnectAction() {
        assertTrue(isVpnDisconnectablePhase(ConnectionPhase.RECOVERING_VPN))
        assertTrue(isVpnDisconnectablePhase(ConnectionPhase.CONNECTED))
        assertFalse(isVpnDisconnectablePhase(ConnectionPhase.ESTABLISHING_VPN))
    }

    @Test
    fun initialStateIsDisconnectedWithoutStartingAConnection() {
        val state = ConnectionUiState(
            rememberedUsername = "student",
            username = "student",
        )

        assertEquals(ConnectionPhase.DISCONNECTED, state.phase)
        assertEquals("student", state.rememberedUsername)
        assertEquals("student", state.username)
        assertTrue(state.password.isEmpty())
    }

    @Test
    fun accountSwitchIsAvailableOnlyForDisconnectedRememberedIdentity() {
        assertTrue(
            canSwitchAccount(
                ConnectionUiState(
                    phase = ConnectionPhase.DISCONNECTED,
                    rememberedUsername = "student",
                ),
            ),
        )
        assertFalse(canSwitchAccount(ConnectionUiState(rememberedUsername = "")))
        assertFalse(
            canSwitchAccount(
                ConnectionUiState(
                    phase = ConnectionPhase.CONNECTED,
                    rememberedUsername = "student",
                ),
            ),
        )
        assertFalse(
            canSwitchAccount(
                ConnectionUiState(
                    phase = ConnectionPhase.ERROR,
                    rememberedUsername = "student",
                ),
            ),
        )
    }

    @Test
    fun accountSwitchPendingStateRemovesPriorIdentityAndSensitiveInputs() {
        val pending = accountSwitchPendingState(
            ConnectionUiState(
                phase = ConnectionPhase.DISCONNECTED,
                rememberedUsername = "student",
                username = "student",
                password = "secret",
                phone = "13800000000",
                smsCode = "123456",
                token = "654321",
                challengeKind = "auth/totp",
                phoneNumbers = listOf("138****0000"),
                captchaImage = byteArrayOf(1, 2, 3),
                captchaWidth = 20,
                captchaHeight = 10,
                captchaPoints = listOf(CaptchaPoint(1, 2)),
            ),
        )

        assertEquals(ConnectionPhase.FETCHING_AUTH_METHODS, pending.phase)
        assertEquals("", pending.rememberedUsername)
        assertEquals("", pending.username)
        assertTrue(pending.password.isEmpty())
        assertTrue(pending.phone.isEmpty())
        assertTrue(pending.smsCode.isEmpty())
        assertTrue(pending.token.isEmpty())
        assertTrue(pending.challengeKind.isEmpty())
        assertTrue(pending.phoneNumbers.isEmpty())
        assertNull(pending.captchaImage)
        assertTrue(pending.captchaPoints.isEmpty())
    }

    @Test
    fun accountSwitchClearFailureRetriesClearingInsteadOfRestoringOldSession() {
        assertTrue(
            shouldRetryAccountSwitchClear(
                ConnectionUiState(
                    phase = ConnectionPhase.ERROR,
                    internalCode = "accountSwitchClearFailed",
                ),
            ),
        )
        assertFalse(shouldRetryAccountSwitchClear(ConnectionUiState(phase = ConnectionPhase.ERROR)))
        assertFalse(
            shouldRetryAccountSwitchClear(
                ConnectionUiState(
                    phase = ConnectionPhase.DISCONNECTED,
                    internalCode = "accountSwitchClearFailed",
                ),
            ),
        )
    }

    @Test
    fun onlyAuthenticationInputPhasesUseScrollableHomeLayout() {
        val inputPhases = setOf(
            ConnectionPhase.AWAITING_CREDENTIALS,
            ConnectionPhase.AWAITING_PHONE,
            ConnectionPhase.AWAITING_SMS,
            ConnectionPhase.AWAITING_TOKEN,
            ConnectionPhase.AWAITING_CAPTCHA,
        )

        ConnectionPhase.entries.forEach { phase ->
            assertEquals(phase in inputPhases, usesScrollableHomeLayout(phase))
        }
    }

    @Test
    fun stableStatusUsesRememberedAccountAsItsOnlySupportingLine() {
        val disconnected = ConnectionUiState(
            rememberedUsername = "student",
            statusMessage = "尚未连接",
        )
        val connected = disconnected.copy(
            phase = ConnectionPhase.CONNECTED,
            statusMessage = "已连接到浙江大学 VPN",
        )

        assertEquals("账号：student", connectionSupportingText(disconnected))
        assertEquals("账号：student", connectionSupportingText(connected))
        assertEquals(
            "尚未保存账号",
            connectionSupportingText(ConnectionUiState()),
        )
    }

    @Test
    fun transientAndErrorStatusKeepTheirActionableDetail() {
        val restoring = ConnectionUiState(
            phase = ConnectionPhase.RESTORING_SESSION,
            statusMessage = "正在验证已保存的登录状态…",
            rememberedUsername = "student",
        )
        val error = restoring.copy(
            phase = ConnectionPhase.ERROR,
            statusMessage = "暂时无法连接，请检查网络后重试。",
        )

        assertEquals("正在验证已保存的登录状态…", connectionSupportingText(restoring))
        assertEquals("暂时无法连接，请检查网络后重试。", connectionSupportingText(error))
    }

    @Test
    fun radiusPasswordMethodWinsRegardlessOfServerOrder() {
        val radius = GoAuthMethod("auth/psw", "Radius", "Account")
        val selected = selectAutomaticAuthMethod(
            listOf(
                GoAuthMethod("auth/smsCheckCode", "SMS", "SMS"),
                radius,
            ),
        )

        assertEquals(radius, selected)
    }

    @Test
    fun uniqueSupportedMethodIsTheOnlyFallback() {
        val sms = GoAuthMethod("auth/smsCheckCode", "Mobile", "SMS")

        assertEquals(
            sms,
            selectAutomaticAuthMethod(
                listOf(
                    GoAuthMethod("auth/cas", "ZJU", "CAS"),
                    sms,
                ),
            ),
        )
        assertNull(
            selectAutomaticAuthMethod(
                listOf(
                    GoAuthMethod("auth/psw", "Other", "Other account"),
                    sms,
                ),
            ),
        )
    }

    @Test
    fun onlyDefinitivelyInvalidStoredSessionsAreDeleted() {
        assertTrue(isDefinitivelyInvalidStoredSession("sessionInvalid", "sessionInvalid"))
        assertTrue(isDefinitivelyInvalidStoredSession("error", "invalidSession"))
        assertFalse(isDefinitivelyInvalidStoredSession("authMethodsReady", "sessionExpired"))
        assertFalse(isDefinitivelyInvalidStoredSession("error", "sessionRestoreUnavailable"))
        assertFalse(isDefinitivelyInvalidStoredSession("error", "certificateRejected"))
        assertFalse(isDefinitivelyInvalidStoredSession("error", "authDnsFailure"))
        assertFalse(isDefinitivelyInvalidStoredSession("error", "authNetworkFailure"))
    }

    @Test
    fun reusableSessionFallsBackOnlyForAuthenticationRejection() {
        assertTrue(shouldFallbackFromReusableAuthentication("vpnSessionInvalid", "authentication"))
        assertTrue(shouldFallbackFromReusableAuthentication("vpnSetupFailed", "serverRejected"))
        assertFalse(shouldFallbackFromReusableAuthentication("vpnSetupFailed", "timeout"))
        assertFalse(shouldFallbackFromReusableAuthentication("vpnSetupFailed", "networkUnavailable"))
        assertFalse(shouldFallbackFromReusableAuthentication("vpnSetupFailed", "tlsValidation"))
    }

    @Test
    fun onlyExplicitCredentialRejectionClearsSavedPassword() {
        assertTrue(isCredentialExplicitlyRejected("credentialsRejected"))
        assertFalse(isCredentialExplicitlyRejected("sessionExpired"))
        assertFalse(isCredentialExplicitlyRejected("certificateRejected"))
        assertFalse(isCredentialExplicitlyRejected("sessionRestoreUnavailable"))
    }

    @Test
    fun savedCredentialCannotCrossRememberedAccounts() {
        val credential = StoredCredential("student-a", "secret")

        assertTrue(savedCredentialMatchesAccount(credential, ""))
        assertTrue(savedCredentialMatchesAccount(credential, "student-a"))
        assertFalse(savedCredentialMatchesAccount(credential, "student-b"))
    }

    @Test
    fun cancellationInvalidatesLateCallbacks() {
        val attempts = ConnectionAttemptTracker()
        val first = attempts.begin()

        val afterCancellation = attempts.invalidate()

        assertFalse(attempts.accepts(first))
        assertTrue(attempts.accepts(afterCancellation))
    }

    @Test
    fun sessionSaveFailureStillContinuesToVpnPermission() {
        val saved = authenticatedContinuation(sessionSaved = true, usernameSaved = true)
        val failed = authenticatedContinuation(sessionSaved = false, usernameSaved = true)

        assertTrue(saved.requestVpnPermission)
        assertTrue(failed.requestVpnPermission)
        assertTrue(saved.notice.isEmpty())
        assertTrue(failed.notice.contains("下次"))
    }

    @Test
    fun userFacingErrorsNeverExposeInternalCodes() {
        val internalCodes = listOf(
            "vpnPermissionDenied",
            "authDnsFailure",
            "authNetworkFailure",
            "authNetworkTimeout",
            "authProtocolFailure",
            "authServerFailure",
            "certificateRejected",
            "unsupportedAuthMethod",
            "sessionRestoreUnavailable",
            "deviceIdentityUnavailable",
            "credentialStoreUnavailable",
            "vpnTunWriteFailed",
            "unexpectedInternalCode",
        )

        internalCodes.forEach { code ->
            val message = connectionErrorMessage(code)
            assertTrue(message.isNotBlank())
            assertFalse("message leaked internal code $code", message.contains(code))
        }
    }

    @Test
    fun tokenChallengesUseServerSpecificPrompts() {
        assertEquals("请输入动态认证码", tokenChallengeMessage("auth/totp"))
        assertEquals("请输入 RADIUS 认证码", tokenChallengeMessage("auth/radius"))
        assertEquals("请输入服务端挑战码", tokenChallengeMessage("auth/challenge"))
        assertEquals("请输入服务端要求的认证码", tokenChallengeMessage("auth/unknown"))
    }

    @Test
    fun realVpnStorePublishesStateFlowUpdates() {
        RealVpnStateStore.reset()

        RealVpnStateStore.setStatus("active", "active")

        assertEquals("active", RealVpnStateStore.state.value.state)
        assertEquals("active", RealVpnStateStore.state.value.message)
        RealVpnStateStore.reset()
    }
}
