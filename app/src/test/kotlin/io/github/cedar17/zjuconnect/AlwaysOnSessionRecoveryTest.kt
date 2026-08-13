package io.github.cedar17.zjuconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlwaysOnSessionRecoveryTest {
    @Test
    fun restoreEventsAreSeparatedIntoUserAuthInvalidAndTransientFailures() {
        assertEquals(
            AlwaysOnSessionRestoreOutcome.WaitingForUserAuthentication,
            classifyAlwaysOnSessionRestoreEvent(
                GoAuthEvent(
                    type = "credentialsRequired",
                    state = "awaitingCredentials",
                    code = "",
                    message = "credentials",
                ),
            ),
        )
        assertEquals(
            AlwaysOnSessionRestoreOutcome.InvalidSession,
            classifyAlwaysOnSessionRestoreEvent(
                GoAuthEvent(
                    type = "sessionInvalid",
                    state = "idle",
                    code = "sessionInvalid",
                    message = "expired",
                ),
            ),
        )
        assertEquals(
            AlwaysOnSessionRestoreOutcome.TransientFailure,
            classifyAlwaysOnSessionRestoreEvent(
                GoAuthEvent(
                    type = "error",
                    state = "error",
                    code = "sessionRestoreUnavailable",
                    message = "network",
                ),
            ),
        )
        assertEquals(
            AlwaysOnSessionRestoreOutcome.Authenticated,
            classifyAlwaysOnSessionRestoreEvent(
                GoAuthEvent(
                    type = "authenticated",
                    state = "authenticated",
                    code = "",
                    message = "ok",
                ),
            ),
        )
        assertNull(
            classifyAlwaysOnSessionRestoreEvent(
                GoAuthEvent(
                    type = "sessionRestoreStarted",
                    state = "restoringSession",
                    code = "",
                    message = "started",
                ),
            ),
        )
    }

    @Test
    fun retryPolicyUsesThreeAttemptsAndResetsOnNetworkRevision() {
        val policy = AlwaysOnRestoreRetryPolicy()

        assertEquals(0L, policy.nextDelayFor(7L))
        assertEquals(5_000L, policy.nextDelayFor(7L))
        assertEquals(30_000L, policy.nextDelayFor(7L))
        assertNull(policy.nextDelayFor(7L))
        assertEquals(true, policy.isExhaustedFor(7L))
        assertEquals(0L, policy.nextDelayFor(8L))
    }
}
