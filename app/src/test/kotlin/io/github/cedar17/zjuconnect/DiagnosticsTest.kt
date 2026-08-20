package io.github.cedar17.zjuconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    @Test
    fun preparationTimeoutKeepsOnlyItsFixedStageAndDuration() {
        val event = RedactedDiagnosticEvent(
            timestampMillis = 42,
            category = "connection",
            state = "error",
            code = "vpnPrepareTimeout",
            stage = "prepare.nodeProbe",
            cause = "timeout",
            durationMillis = 30_000,
        ).redacted()

        assertEquals("vpnPrepareTimeout", event.code)
        assertEquals("prepare.nodeProbe", event.stage)
        assertEquals("timeout", event.cause)
        assertEquals(30_000L, event.durationMillis)
    }

    @Test
    fun ringBufferRetainsOnlyTheMostRecentOneHundredEvents() {
        val ring = RedactedDiagnosticRingBuffer()

        repeat(MAX_REDACTED_DIAGNOSTIC_EVENTS + 1) { index ->
            ring.append(
                RedactedDiagnosticEvent(
                    timestampMillis = index.toLong(),
                    category = "connection",
                    state = "disconnected",
                ),
            )
        }

        assertEquals(MAX_REDACTED_DIAGNOSTIC_EVENTS, ring.snapshot().size)
        assertEquals(1L, ring.snapshot().first().timestampMillis)
        assertEquals(MAX_REDACTED_DIAGNOSTIC_EVENTS.toLong(), ring.snapshot().last().timestampMillis)
        ring.clear()
        assertTrue(ring.snapshot().isEmpty())
    }

    @Test
    fun diagnosticsNormalizeUnknownAndSensitiveLookingFields() {
        val event = RedactedDiagnosticEvent(
            timestampMillis = 42,
            category = "untrusted-category",
            state = "password=secret",
            code = "cookie=abc",
            stage = "dataplane.tun.read/192.168.1.10",
            cause = "10.0.0.1:443",
            durationMillis = -1,
            counters = RedactedDiagnosticCounters(
                tunReadPackets = -1,
                tunReadBytes = 60,
            ),
        ).redacted()

        assertEquals("connection", event.category)
        assertEquals("unknown", event.state)
        assertEquals("unknown", event.code)
        assertEquals("dataplane", event.stage)
        assertTrue(event.cause.isEmpty())
        assertEquals(0L, event.durationMillis)
        assertEquals(0L, event.counters?.tunReadPackets)
        assertEquals(60L, event.counters?.tunReadBytes)
    }

    @Test
    fun publicReportContainsOnlyRedactedEventFields() {
        val report = formatRedactedDiagnosticReport(
            environment = DiagnosticReportEnvironment(
                appVersion = "0.1.0 (1)",
                androidRelease = "15",
                apiLevel = 35,
                manufacturer = "Example",
                model = "Example Device",
            ),
            events = listOf(
                RedactedDiagnosticEvent(
                    timestampMillis = 42,
                    category = "vpn",
                    state = "diagnostic",
                    code = "password=secret",
                    stage = "dataplane.l3.write",
                    cause = "fdClosed",
                    durationMillis = 1_234,
                    counters = RedactedDiagnosticCounters(
                        tunReadPackets = 3,
                        tunReadBytes = 180,
                        l3WriteSuccesses = 1,
                    ),
                ),
            ),
            generatedAtMillis = 1_000,
        )

        assertTrue(report.contains("state=diagnostic"))
        assertTrue(report.contains("code=unknown"))
        assertTrue(report.contains("stage=dataplane"))
        assertTrue(report.contains("cause=fdClosed"))
        assertTrue(report.contains("durationMs=1234"))
        assertTrue(report.contains("tunRead=3/180"))
        for (forbidden in listOf("password=secret", "192.168", "cookie", "sid", "payload")) {
            assertFalse("report contained forbidden value $forbidden: $report", report.contains(forbidden))
        }
    }

    @Test
    fun summaryUsesTheNewestEventWithoutChangingTheStoredEventCount() {
        val events = listOf(
            RedactedDiagnosticEvent(
                timestampMillis = 1,
                category = "connection",
                state = "disconnected",
            ),
            RedactedDiagnosticEvent(
                timestampMillis = 2,
                category = "connection",
                state = "awaiting_captcha",
            ),
        )

        val summary = diagnosticSummary(events)

        assertEquals(2, summary.eventCount)
        val latest = requireNotNull(summary.latest)
        assertEquals("awaiting_captcha", latest.state)
    }

    @Test
    fun resourceBackedPresentationCoversRepresentativeStatesAndFallbacks() {
        assertEquals(
            UiText.Resource(R.string.diagnostic_connection_connected),
            diagnosticStateText(
                RedactedDiagnosticEvent(
                    timestampMillis = 1,
                    category = "connection",
                    state = "connected",
                ),
            ),
        )
        assertEquals(
            UiText.Resource(R.string.diagnostic_vpn_waiting_network),
            diagnosticStateText(
                RedactedDiagnosticEvent(
                    timestampMillis = 2,
                    category = "vpn",
                    state = "waitingForNetwork",
                ),
            ),
        )
        assertEquals(
            UiText.Resource(R.string.diagnostic_unknown_state),
            diagnosticStateText(
                RedactedDiagnosticEvent(
                    timestampMillis = 3,
                    category = "connection",
                    state = "future_state",
                ),
            ),
        )
        assertEquals(
            UiText.Resource(R.string.diagnostic_category_auth_recovery),
            diagnosticCategoryText("authRecovery"),
        )
    }

    @Test
    fun consecutiveEquivalentEventsCollapseButTimestampChangesRemainInCopyData() {
        val events = listOf(
            RedactedDiagnosticEvent(
                timestampMillis = 1,
                category = "connection",
                state = "authenticating",
            ),
            RedactedDiagnosticEvent(
                timestampMillis = 2,
                category = "connection",
                state = "authenticating",
            ),
            RedactedDiagnosticEvent(
                timestampMillis = 3,
                category = "connection",
                state = "awaiting_credentials",
            ),
        )

        val groups = collapseConsecutiveDiagnosticEvents(events)

        assertEquals(2, groups.size)
        assertEquals(2, groups.first().occurrences)
        assertEquals(1L, groups.first().event.timestampMillis)
        assertEquals(3L, groups.last().event.timestampMillis)
    }

    @Test
    fun displayGroupingIgnoresChangingDataPlaneCounters() {
        val events = listOf(
            RedactedDiagnosticEvent(
                timestampMillis = 1,
                category = "vpn",
                state = "diagnostic",
                stage = "dataplane.l3.read",
                counters = RedactedDiagnosticCounters(l3ReadPackets = 1),
            ),
            RedactedDiagnosticEvent(
                timestampMillis = 2,
                category = "vpn",
                state = "diagnostic",
                stage = "dataplane.l3.read",
                counters = RedactedDiagnosticCounters(l3ReadPackets = 2),
            ),
        )

        val groups = collapseConsecutiveDiagnosticEvents(events)

        assertEquals(1, groups.size)
        assertEquals(2, groups.single().occurrences)
    }

    @Test
    fun displayGroupingKeepsDifferentDurationsVisible() {
        val events = listOf(
            RedactedDiagnosticEvent(
                timestampMillis = 1,
                category = "connection",
                state = "error",
                code = "authNetworkTimeout",
                stage = "auth.config",
                cause = "timeout",
                durationMillis = 8_000,
            ),
            RedactedDiagnosticEvent(
                timestampMillis = 2,
                category = "connection",
                state = "error",
                code = "authNetworkTimeout",
                stage = "auth.config",
                cause = "timeout",
                durationMillis = 20_000,
            ),
        )

        val groups = collapseConsecutiveDiagnosticEvents(events)

        assertEquals(2, groups.size)
        assertEquals(8_000L, groups.first().event.durationMillis)
        assertEquals(20_000L, groups.last().event.durationMillis)
    }

    @Test
    fun previewKeepsOnlyTheMostRecentStateChanges() {
        val events = (0 until MAX_DIAGNOSTIC_PREVIEW_GROUPS + 2).map { index ->
            RedactedDiagnosticEvent(
                timestampMillis = index.toLong(),
                category = "connection",
                state = if (index % 2 == 0) "disconnected" else "connected",
                code = "unknown",
            )
        }

        val preview = diagnosticPreviewGroups(events)

        assertEquals(MAX_DIAGNOSTIC_PREVIEW_GROUPS, preview.size)
        assertEquals(2L, preview.first().event.timestampMillis)
        assertEquals((MAX_DIAGNOSTIC_PREVIEW_GROUPS + 1).toLong(), preview.last().event.timestampMillis)
    }

    @Test
    fun recoveryStatesRemainVisibleAfterRedaction() {
        val recovering = RedactedDiagnosticEvent(
            timestampMillis = 1,
            category = "service",
            state = "recovering",
        ).redacted()
        val waiting = RedactedDiagnosticEvent(
            timestampMillis = 2,
            category = "service",
            state = "waitingForNetwork",
        ).redacted()

        assertEquals("recovering", recovering.state)
        assertEquals("waitingForNetwork", waiting.state)
        assertEquals(
            UiText.Resource(R.string.diagnostic_service_recovering),
            diagnosticStateText(recovering),
        )
        assertEquals(
            UiText.Resource(R.string.diagnostic_service_waiting_network),
            diagnosticStateText(waiting),
        )
    }

    @Test
    fun l3RecoveryCodesAndFailureCategoriesRemainSafeAndVisible() {
        val recovering = RedactedDiagnosticEvent(
            timestampMillis = 1,
            category = "vpn",
            state = "recovering",
            code = "l3Reconnecting",
            stage = "dataplane.l3.reconnect",
            cause = "l3Recovering",
        ).redacted()
        val failed = RedactedDiagnosticEvent(
            timestampMillis = 2,
            category = "vpn",
            state = "error",
            code = "vpnSessionInvalid",
            stage = "dataplane.l3.reconnect",
            cause = "authentication",
        ).redacted()

        assertEquals("l3Reconnecting", recovering.code)
        assertEquals("l3Recovering", recovering.cause)
        assertEquals("vpnSessionInvalid", failed.code)
        assertEquals("authentication", failed.cause)
        assertEquals("dataplane", failed.stage)
    }

    @Test
    fun authenticationRecoveryDiagnosticsUseOnlyStableAllowlistedLabels() {
        val stale = RedactedDiagnosticEvent(
            timestampMillis = 1,
            category = "authRecovery",
            state = "persisted_session_stale",
            code = "reauthenticating",
            cause = "credentialsRejected",
        ).redacted()
        val unsafe = RedactedDiagnosticEvent(
            timestampMillis = 2,
            category = "authRecovery",
            state = "sid=secret",
            code = "password=secret",
            cause = "deviceId=secret",
        ).redacted()

        assertEquals("authRecovery", stale.category)
        assertEquals("persisted_session_stale", stale.state)
        assertEquals("reauthenticating", stale.code)
        assertEquals("credentialsRejected", stale.cause)
        assertEquals(
            UiText.Resource(R.string.diagnostic_category_auth_recovery),
            diagnosticCategoryText(stale.category),
        )
        assertEquals(
            UiText.Resource(R.string.diagnostic_auth_persisted_session_stale),
            diagnosticStateText(stale),
        )
        assertEquals("unknown", unsafe.state)
        assertEquals("unknown", unsafe.code)
        assertTrue(unsafe.cause.isEmpty())
    }
}
