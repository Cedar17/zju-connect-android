package cn.zju.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPolicyTest {
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
        assertEquals(
            StoredSessionFailureAction.DELETE_AND_REAUTHENTICATE,
            storedSessionFailureAction("sessionInvalid", "sessionInvalid"),
        )
        assertEquals(
            StoredSessionFailureAction.DELETE_AND_REAUTHENTICATE,
            storedSessionFailureAction("error", "invalidSession"),
        )
        assertEquals(
            StoredSessionFailureAction.RETAIN_AND_SHOW_ERROR,
            storedSessionFailureAction("error", "sessionRestoreUnavailable"),
        )
        assertEquals(
            StoredSessionFailureAction.RETAIN_AND_SHOW_ERROR,
            storedSessionFailureAction("error", "certificateRejected"),
        )
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
            "certificateRejected",
            "unsupportedAuthMethod",
            "sessionRestoreUnavailable",
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
    fun realVpnStorePublishesStateFlowUpdates() {
        RealVpnStateStore.reset()

        RealVpnStateStore.setStatus("active", "active")

        assertEquals("active", RealVpnStateStore.state.value.state)
        assertEquals("active", RealVpnStateStore.state.value.message)
        RealVpnStateStore.reset()
    }
}
