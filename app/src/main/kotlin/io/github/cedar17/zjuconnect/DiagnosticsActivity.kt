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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
                        Toast.makeText(this, "诊断记录已清除", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }

    private fun copyReport(report: String) {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("ZJU Connect diagnostics", report))
        Toast.makeText(this, "诊断报告已复制", Toast.LENGTH_SHORT).show()
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
                    title = { Text("诊断") },
                    navigationIcon = {
                        TextButton(onClick = onBack) { Text("返回") }
                    },
                    actions = {
                        TextButton(
                            onClick = { onCopy(report) },
                            enabled = snapshot.loaded,
                        ) {
                            Text("复制报告")
                        }
                        TextButton(
                            onClick = onClear,
                            enabled = snapshot.loaded && summary.eventCount > 0,
                        ) {
                            Text("清除")
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
                Text(
                    text = "页面摘要可直接截图；复制报告包含完整的白名单记录。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

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
                text = "最近状态",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = when {
                    !loaded -> "正在读取…"
                    latest == null -> "暂无诊断记录"
                    else -> diagnosticStateLabel(latest)
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
            )
            when {
                !loaded -> Unit
                latest == null -> Text(
                    text = "开始连接后，白名单状态会显示在这里。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    Text(
                        text = "最近更新 ${formatDiagnosticDisplayTime(latest.timestampMillis)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    diagnosticEventDetails(latest)?.let { details ->
                        Text(
                            text = details,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                    text = "最近记录",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = when {
                        !loaded -> "正在读取…"
                        summary.eventCount == 0 -> "暂无记录"
                        else -> "${summary.eventCount} 条记录"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (loaded && groups.size > MAX_DIAGNOSTIC_PREVIEW_GROUPS) {
                TextButton(onClick = onToggle) {
                    Text(if (showAllEvents) "收起" else "查看全部")
                }
            }
        }

        when {
            !loaded -> Text(
                text = "正在读取本机诊断记录…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            groups.isEmpty() -> Text(
                text = "暂无记录。连接或断开后，相关状态会显示在这里。",
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
                    Text(
                        text = visibleGroups.joinToString("\n", transform = ::formatDiagnosticPreviewLine),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
                if (!showAllEvents && groups.size > MAX_DIAGNOSTIC_PREVIEW_GROUPS) {
                    Text(
                        text = "默认仅显示最近状态变化；连续重复状态会合并。完整记录请使用“复制报告”。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun diagnosticEventDetails(event: RedactedDiagnosticEvent): String? {
    val details = buildList {
        event.code.takeIf(String::isNotBlank)?.let { add("错误码 $it") }
        event.stage.takeIf(String::isNotBlank)?.let { add("阶段 $it") }
        event.cause.takeIf(String::isNotBlank)?.let { add("原因 $it") }
    }
    return details.takeIf { it.isNotEmpty() }?.joinToString(" · ")
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
