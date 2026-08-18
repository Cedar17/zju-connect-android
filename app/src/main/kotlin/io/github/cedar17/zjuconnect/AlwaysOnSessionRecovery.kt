package io.github.cedar17.zjuconnect

import java.util.concurrent.atomic.AtomicLong

internal enum class AlwaysOnSessionRestoreOutcome {
    Authenticated,
    WaitingForUserAuthentication,
    InvalidSession,
    TransientFailure,
}

internal data class AlwaysOnSessionRestoreResult(
    val outcome: AlwaysOnSessionRestoreOutcome,
    val code: String = "",
    val message: String = "",
)

/** Maps only the safe, structured Go authentication events used by recovery. */
internal fun classifyAlwaysOnSessionRestoreEvent(event: GoAuthEvent): AlwaysOnSessionRestoreOutcome? = when {
    event.type == "authenticated" -> AlwaysOnSessionRestoreOutcome.Authenticated
    event.type == "sessionInvalid" || event.code in setOf("invalidSession", "sessionInvalid") ->
        AlwaysOnSessionRestoreOutcome.InvalidSession
    event.type == "authMethodsReady" && event.code == "sessionExpired" ->
        AlwaysOnSessionRestoreOutcome.WaitingForUserAuthentication
    event.type in setOf(
        "authMethodsReady",
        "credentialsRequired",
        "phoneRequired",
        "smsRequired",
        "tokenRequired",
        "captchaRequired",
    ) -> AlwaysOnSessionRestoreOutcome.WaitingForUserAuthentication
    event.type == "error" -> AlwaysOnSessionRestoreOutcome.TransientFailure
    else -> null
}

internal const val ALWAYS_ON_MAX_RESTORE_ATTEMPTS = 3

private val ALWAYS_ON_RESTORE_DELAYS_MILLIS = longArrayOf(0L, 5_000L, 30_000L)

internal fun alwaysOnRestoreDelayMillis(attemptIndex: Int): Long? =
    ALWAYS_ON_RESTORE_DELAYS_MILLIS.getOrNull(attemptIndex)

internal fun shouldClearAlwaysOnStoredSession(outcome: AlwaysOnSessionRestoreOutcome): Boolean =
    outcome == AlwaysOnSessionRestoreOutcome.InvalidSession

/** Limits cold-start session validation to one bounded sequence per network revision. */
internal class AlwaysOnRestoreRetryPolicy {
    private var revision = Long.MIN_VALUE
    private var nextAttemptIndex = 0

    fun resetForRevision(nextRevision: Long) {
        revision = nextRevision
        nextAttemptIndex = 0
    }

    fun nextDelayFor(nextRevision: Long): Long? {
        if (revision != nextRevision) resetForRevision(nextRevision)
        val delay = alwaysOnRestoreDelayMillis(nextAttemptIndex) ?: return null
        nextAttemptIndex += 1
        return delay
    }

    fun isExhaustedFor(currentRevision: Long): Boolean =
        revision == currentRevision && nextAttemptIndex >= ALWAYS_ON_MAX_RESTORE_ATTEMPTS
}

/**
 * Service-owned session handoff. It deliberately knows nothing about saved
 * passwords or UI prompts: a prompt, including an expired SID, becomes a
 * waiting result and preserves the encrypted client context for the foreground
 * Activity. The Go flow is then cancelled without background credential use.
 */
internal class AlwaysOnSessionRestorer(
    private val sessionStore: AuthSessionStore,
    private val deviceIdentityProvider: DeviceIdentityProvider,
    private val bridge: GoCoreBridge,
) {
    private val generation = AtomicLong(0)

    fun invalidate() {
        generation.incrementAndGet()
    }

    fun cancel() {
        invalidate()
        runCatching { bridge.cancelAuthentication() }
    }

    fun start(onResult: (AlwaysOnSessionRestoreResult) -> Unit) {
        val token = generation.incrementAndGet()
        fun finish(result: AlwaysOnSessionRestoreResult) {
            if (!generation.compareAndSet(token, token + 1)) return
            if (shouldClearAlwaysOnStoredSession(result.outcome)) {
                runCatching { sessionStore.clear() }
            }
            if (result.outcome != AlwaysOnSessionRestoreOutcome.Authenticated) {
                runCatching { bridge.cancelAuthentication() }
            }
            onResult(result)
        }

        var snapshot: ByteArray? = null
        try {
            snapshot = sessionStore.read()
            if (snapshot == null) {
                finish(
                    AlwaysOnSessionRestoreResult(
                        outcome = AlwaysOnSessionRestoreOutcome.WaitingForUserAuthentication,
                        code = "authenticationRequired",
                        message = "No saved authentication session is available",
                    ),
                )
                return
            }

            val deviceID = deviceIdentityProvider.read()
            if (deviceID.isNullOrBlank()) {
                finish(
                    AlwaysOnSessionRestoreResult(
                        outcome = AlwaysOnSessionRestoreOutcome.TransientFailure,
                        code = "deviceIdentityUnavailable",
                        message = "The device identity is unavailable",
                    ),
                )
                return
            }

            val initialEvent = bridge.resumeAuthentication(snapshot, deviceID) { event ->
                classifyAlwaysOnSessionRestoreEvent(event)?.let { outcome ->
                    finish(
                        AlwaysOnSessionRestoreResult(
                            outcome = outcome,
                            code = event.code,
                            message = event.message,
                        ),
                    )
                }
            }
            classifyAlwaysOnSessionRestoreEvent(initialEvent)?.let { outcome ->
                finish(
                    AlwaysOnSessionRestoreResult(
                        outcome = outcome,
                        code = initialEvent.code,
                        message = initialEvent.message,
                    ),
                )
            }
        } catch (_: InvalidStoredAuthenticationSession) {
            finish(
                AlwaysOnSessionRestoreResult(
                    outcome = AlwaysOnSessionRestoreOutcome.InvalidSession,
                    code = "invalidSession",
                    message = "The saved authentication session is invalid",
                ),
            )
        } catch (error: Throwable) {
            finish(
                AlwaysOnSessionRestoreResult(
                    outcome = AlwaysOnSessionRestoreOutcome.TransientFailure,
                    code = "sessionRestoreUnavailable",
                    message = error.message.orEmpty(),
                ),
            )
        } finally {
            snapshot?.fill(0)
        }
    }
}
