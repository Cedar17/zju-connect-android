package io.github.cedar17.zjuconnect

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

class DiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val snapshot by RedactedDiagnostics.observe(this).collectAsState()
            val environment = diagnosticReportEnvironment(this)
            ZjuConnectTheme {
                DiagnosticsScreen(
                    snapshot = snapshot,
                    environment = environment,
                    onBack = ::finish,
                    onCopy = { report -> copyReport(report) },
                    onClear = {
                        RedactedDiagnostics.clear(this)
                        Toast.makeText(this, getString(R.string.diagnostics_cleared), Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }

    private fun copyReport(report: String) {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("ZJU Connect diagnostics", report))
        Toast.makeText(this, getString(R.string.diagnostics_copied), Toast.LENGTH_SHORT).show()
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DiagnosticsScreen(
    snapshot: RedactedDiagnosticsSnapshot,
    environment: DiagnosticReportEnvironment,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    onClear: () -> Unit,
) {
    val report = formatRedactedDiagnosticReport(environment, snapshot.events)
    val summary = diagnosticSummary(snapshot.events)
    val groupedEvents = collapseConsecutiveDiagnosticEvents(snapshot.events)
    val previewGroups = diagnosticPreviewGroups(snapshot.events)
    var showAllEvents by rememberSaveable { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.diagnostics_title)) },
                    navigationIcon = {
                        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                    },
                    actions = {
                        TextButton(
                            onClick = { onCopy(report) },
                            enabled = snapshot.loaded,
                        ) {
                            Text(stringResource(R.string.copy_report))
                        }
                        TextButton(
                            onClick = onClear,
                            enabled = snapshot.loaded && summary.eventCount > 0,
                        ) {
                            Text(stringResource(R.string.clear))
                        }
                    },
                )
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding)
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                DiagnosticSummaryCard(
                    loaded = snapshot.loaded,
                    summary = summary,
                )

                DiagnosticHistorySection(
                    loaded = snapshot.loaded,
                    summary = summary,
                    groups = groupedEvents,
                    previewGroups = previewGroups,
                    showAllEvents = showAllEvents,
                    onToggle = { showAllEvents = !showAllEvents },
                )
            }
        }
    }
}

@Composable
private fun DiagnosticSummaryCard(
    loaded: Boolean,
    summary: DiagnosticSummary,
) {
    val latest = summary.latest
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.diagnostics_latest_status),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = when {
                    !loaded -> stringResource(R.string.loading)
                    latest == null -> stringResource(R.string.diagnostics_no_record)
                    else -> diagnosticStateText(latest).resolve()
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
            )
            when {
                !loaded -> Unit
                latest == null -> Text(
                    text = stringResource(R.string.diagnostics_start_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    Text(
                        text = stringResource(
                            R.string.diagnostics_updated_at,
                            formatDiagnosticDisplayTime(latest.timestampMillis),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DiagnosticEventDetails(latest)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticHistorySection(
    loaded: Boolean,
    summary: DiagnosticSummary,
    groups: List<DiagnosticEventGroup>,
    previewGroups: List<DiagnosticEventGroup>,
    showAllEvents: Boolean,
    onToggle: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.diagnostics_recent_records),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = when {
                        !loaded -> stringResource(R.string.loading)
                        summary.eventCount == 0 -> stringResource(R.string.diagnostics_no_records)
                        else -> stringResource(R.string.diagnostics_record_count, summary.eventCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (loaded && groups.size > MAX_DIAGNOSTIC_PREVIEW_GROUPS) {
                TextButton(onClick = onToggle) {
                    Text(
                        stringResource(
                            if (showAllEvents) R.string.diagnostics_collapse else R.string.diagnostics_show_all,
                        ),
                    )
                }
            }
        }

        when {
            !loaded -> Text(
                text = stringResource(R.string.diagnostics_reading_local),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            groups.isEmpty() -> Text(
                text = stringResource(R.string.diagnostics_no_record_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                val visibleGroups = if (showAllEvents) {
                    groups
                } else {
                    previewGroups
                }
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        visibleGroups.forEach { group ->
                            DiagnosticHistoryLine(group)
                        }
                    }
                }
                if (!showAllEvents && groups.size > MAX_DIAGNOSTIC_PREVIEW_GROUPS) {
                    Text(
                        text = stringResource(R.string.diagnostics_history_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticHistoryLine(group: DiagnosticEventGroup) {
    Text(
        text = formatDiagnosticPreviewLineLocalized(group),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
    )
}

@Composable
private fun DiagnosticEventDetails(event: RedactedDiagnosticEvent) {
    val details = buildList {
        event.code.takeIf(String::isNotBlank)?.let {
            add(stringResource(R.string.diagnostic_error_code, diagnosticErrorText(it).resolve()))
        }
        event.durationMillis.takeIf { it > 0 }?.let {
            add(stringResource(R.string.diagnostic_duration, formatDiagnosticDuration(it)))
        }
    }
    details.takeIf { it.isNotEmpty() }?.let {
        Text(
            text = it.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun formatDiagnosticDuration(durationMillis: Long): String = when {
    durationMillis < 1_000 -> stringResource(R.string.diagnostic_milliseconds, durationMillis)
    else -> stringResource(R.string.diagnostic_seconds, durationMillis / 1_000.0)
}

@Composable
private fun formatDiagnosticPreviewLineLocalized(group: DiagnosticEventGroup): String {
    val event = group.event
    return buildList {
        add(
            stringResource(
                R.string.diagnostic_preview_header,
                formatDiagnosticPreviewTime(event.timestampMillis),
                diagnosticCategoryText(event.category).resolve(),
                diagnosticStateText(event).resolve(),
            ),
        )
        event.code.takeIf(String::isNotBlank)?.let {
            add(stringResource(R.string.diagnostic_preview_code, diagnosticErrorText(it).resolve()))
        }
        event.durationMillis.takeIf { it > 0 }?.let {
            add(stringResource(R.string.diagnostic_preview_duration, it))
        }
        if (group.occurrences > 1) {
            add(stringResource(R.string.diagnostic_preview_repeats, group.occurrences))
        }
    }.joinToString("  ")
}

@Suppress("DEPRECATION")
internal fun diagnosticReportEnvironment(context: Context): DiagnosticReportEnvironment {
    val packageInfo = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
    val versionCode = packageInfo?.let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else it.versionCode.toLong()
    } ?: 0L
    return DiagnosticReportEnvironment(
        appVersion = "${packageInfo?.versionName.orEmpty().ifBlank { "unknown" }} ($versionCode)",
        androidRelease = Build.VERSION.RELEASE.orEmpty(),
        apiLevel = Build.VERSION.SDK_INT,
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
    )
}
