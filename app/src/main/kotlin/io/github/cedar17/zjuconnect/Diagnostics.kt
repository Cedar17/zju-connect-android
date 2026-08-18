package io.github.cedar17.zjuconnect

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal const val MAX_REDACTED_DIAGNOSTIC_EVENTS = 100
internal const val MAX_DIAGNOSTIC_PREVIEW_GROUPS = 6

private const val MAX_REDACTED_DIAGNOSTIC_BYTES = 64 * 1024
private const val MAX_REDACTED_DIAGNOSTIC_DURATION_MILLIS = 5 * 60 * 1000L
private const val DIAGNOSTIC_FILE_NAME = "redacted-diagnostics.json"
private const val DIAGNOSTIC_FILE_VERSION = 1

private val SAFE_DIAGNOSTIC_CATEGORIES = setOf("connection", "vpn", "service", "authRecovery")
private val SAFE_CONNECTION_STATES = ConnectionPhase.entries.map { it.name.lowercase() }.toSet()
private val SAFE_AUTH_RECOVERY_STATES = AuthenticationRecoverySource.entries
    .map(AuthenticationRecoverySource::diagnosticState)
    .toSet()
private val SAFE_AUTH_RECOVERY_CODES = AuthenticationRecoveryOutcome.entries
    .map(AuthenticationRecoveryOutcome::diagnosticCode)
    .toSet()
private val SAFE_AUTH_RECOVERY_CAUSES = AuthenticationInvalidationCause.entries
    .map(AuthenticationInvalidationCause::diagnosticCause)
    .toSet()
private val SAFE_VPN_STATES = setOf(
    "idle",
    "preparing",
    "attaching",
    "starting",
    "active",
    "stopping",
    "stopped",
    "error",
    "diagnostic",
    "recovering",
    "waitingForNetwork",
    "waitingForAuthentication",
    "alwaysOnDisconnectBlocked",
    "sessionRestore",
)
private val SAFE_DIAGNOSTIC_CODES = setOf(
    "alwaysOnAuthenticationRequired",
    "alwaysOnDisconnectBlocked",
    "authenticationRequired",
    "authenticationFailed",
    "authDnsFailure",
    "authNetworkFailure",
    "authNetworkTimeout",
    "authProtocolFailure",
    "authServerFailure",
    "authInfoUnavailable",
    "certificateRejected",
    "credentialStoreUnavailable",
    "deviceIdentityUnavailable",
    "initializationFailed",
    "invalidEvent",
    "invalidInput",
    "invalidSession",
    "l3Reconnecting",
    "networkMonitorUnavailable",
    "sessionInvalid",
    "sessionRestoreUnavailable",
    "sessionStoreUnavailable",
    "accountSwitchClearFailed",
    "stopTimeout",
    "tunEstablishFailed",
    "tunEstablishTimeout",
    "tunInitializationFailed",
    "unsupportedAuthMethod",
    "vpnAddressUnavailable",
    "vpnConfigurationUnavailable",
    "vpnPermissionDenied",
    "vpnSessionInvalid",
    "vpnPacketForwardFailed",
    "vpnRevoked",
    "vpnRoutesUnavailable",
    "vpnServerReadFailed",
    "vpnServerWriteFailed",
    "vpnSetupFailed",
    "vpnStartDispatchFailed",
    "vpnStartFailed",
    "vpnStopDispatchFailed",
    "vpnTunReadFailed",
    "vpnTunWriteFailed",
) + SAFE_AUTH_RECOVERY_CODES
private val SAFE_DIAGNOSTIC_CAUSES = setOf(
    "authentication",
    "configuration",
    "connectionClosed",
    "dns",
    "fdClosed",
    "invalidPacket",
    "io",
    "l3Recovering",
    "network",
    "packetTooLarge",
    "protocol",
    "server",
    "tls",
    "timeout",
    "tunUnavailable",
    "wouldBlock",
) + SAFE_AUTH_RECOVERY_CAUSES

/**
 * Numeric data-plane totals are safe to publish, unlike the packet metadata
 * from which they were derived. This type deliberately has no endpoint,
 * payload, route, or authentication fields.
 */
internal data class RedactedDiagnosticCounters(
    val tunReadPackets: Long = 0,
    val tunReadBytes: Long = 0,
    val forwardablePackets: Long = 0,
    val filteredPackets: Long = 0,
    val l3WriteAttempts: Long = 0,
    val l3WriteSuccesses: Long = 0,
    val resourceDrops: Long = 0,
    val l3ReadPackets: Long = 0,
    val l3ReadBytes: Long = 0,
    val l3InvalidPackets: Long = 0,
    val tunWriteAttempts: Long = 0,
    val tunWriteSuccesses: Long = 0,
    val tunWriteBytes: Long = 0,
) {
    fun redacted(): RedactedDiagnosticCounters = copy(
        tunReadPackets = tunReadPackets.coerceAtLeast(0),
        tunReadBytes = tunReadBytes.coerceAtLeast(0),
        forwardablePackets = forwardablePackets.coerceAtLeast(0),
        filteredPackets = filteredPackets.coerceAtLeast(0),
        l3WriteAttempts = l3WriteAttempts.coerceAtLeast(0),
        l3WriteSuccesses = l3WriteSuccesses.coerceAtLeast(0),
        resourceDrops = resourceDrops.coerceAtLeast(0),
        l3ReadPackets = l3ReadPackets.coerceAtLeast(0),
        l3ReadBytes = l3ReadBytes.coerceAtLeast(0),
        l3InvalidPackets = l3InvalidPackets.coerceAtLeast(0),
        tunWriteAttempts = tunWriteAttempts.coerceAtLeast(0),
        tunWriteSuccesses = tunWriteSuccesses.coerceAtLeast(0),
        tunWriteBytes = tunWriteBytes.coerceAtLeast(0),
    )
}

/** A strictly allowlisted event suitable for a public issue report. */
internal data class RedactedDiagnosticEvent(
    val timestampMillis: Long,
    val category: String,
    val state: String = "",
    val code: String = "",
    val stage: String = "",
    val cause: String = "",
    val counters: RedactedDiagnosticCounters? = null,
    val durationMillis: Long = 0,
) {
    fun redacted(): RedactedDiagnosticEvent {
        val safeCategory = category.takeIf { it in SAFE_DIAGNOSTIC_CATEGORIES } ?: "connection"
        return copy(
            timestampMillis = timestampMillis.coerceAtLeast(0),
            category = safeCategory,
            state = redactState(safeCategory, state),
            code = redactCode(code),
            stage = redactStage(stage),
            cause = cause.takeIf { it in SAFE_DIAGNOSTIC_CAUSES }.orEmpty(),
            durationMillis = durationMillis.coerceIn(0, MAX_REDACTED_DIAGNOSTIC_DURATION_MILLIS),
            counters = counters?.redacted(),
        )
    }
}

internal class RedactedDiagnosticRingBuffer(
    private val capacity: Int = MAX_REDACTED_DIAGNOSTIC_EVENTS,
) {
    private var events: List<RedactedDiagnosticEvent> = emptyList()

    init {
        require(capacity > 0) { "Diagnostic ring capacity must be positive" }
    }

    fun append(event: RedactedDiagnosticEvent) {
        events = (events + event.redacted()).takeLast(capacity)
    }

    fun replace(replacement: List<RedactedDiagnosticEvent>) {
        events = replacement.map(RedactedDiagnosticEvent::redacted).takeLast(capacity)
    }

    fun clear() {
        events = emptyList()
    }

    fun snapshot(): List<RedactedDiagnosticEvent> = events
}

internal data class RedactedDiagnosticsSnapshot(
    val loaded: Boolean = false,
    val events: List<RedactedDiagnosticEvent> = emptyList(),
)

internal data class DiagnosticReportEnvironment(
    val appVersion: String,
    val androidRelease: String,
    val apiLevel: Int,
    val manufacturer: String,
    val model: String,
)

/**
 * A display-only projection of the event buffer. The persisted/copyable
 * report remains event-for-event; the Activity uses groups so repeated state
 * transitions do not dominate a screenshot.
 */
internal data class DiagnosticEventGroup(
    val event: RedactedDiagnosticEvent,
    val occurrences: Int = 1,
)

internal data class DiagnosticSummary(
    val eventCount: Int,
    val latest: RedactedDiagnosticEvent?,
)

internal fun diagnosticSummary(events: List<RedactedDiagnosticEvent>): DiagnosticSummary {
    val redactedEvents = events.map(RedactedDiagnosticEvent::redacted)
    return DiagnosticSummary(
        eventCount = redactedEvents.size,
        latest = redactedEvents.lastOrNull(),
    )
}

/**
 * Collapses only adjacent equivalent display events; timestamps and data-plane
 * counter snapshots are intentionally ignored, while durations remain visible
 * because they distinguish a fast failure from a timeout. The copied report
 * still keeps every counter snapshot.
 */
internal fun collapseConsecutiveDiagnosticEvents(
    events: List<RedactedDiagnosticEvent>,
): List<DiagnosticEventGroup> {
    val groups = ArrayList<DiagnosticEventGroup>()
    events.map(RedactedDiagnosticEvent::redacted).forEach { event ->
        val previous = groups.lastOrNull()
        if (previous != null && sameDiagnosticOccurrence(previous.event, event)) {
            groups[groups.lastIndex] = previous.copy(occurrences = previous.occurrences + 1)
        } else {
            groups += DiagnosticEventGroup(event)
        }
    }
    return groups
}

internal fun diagnosticPreviewGroups(
    events: List<RedactedDiagnosticEvent>,
): List<DiagnosticEventGroup> = collapseConsecutiveDiagnosticEvents(events)
    .takeLast(MAX_DIAGNOSTIC_PREVIEW_GROUPS)

internal fun diagnosticStateLabel(event: RedactedDiagnosticEvent): String = when (event.category) {
    "connection" -> when (event.state) {
        "disconnected" -> "未连接"
        "restoring_session" -> "恢复会话"
        "fetching_auth_methods" -> "获取认证方式"
        "authenticating" -> "认证中"
        "awaiting_credentials" -> "等待账号密码"
        "awaiting_phone" -> "等待手机号"
        "awaiting_sms" -> "等待短信验证码"
        "awaiting_captcha" -> "等待图形验证码"
        "preparing_vpn_permission" -> "准备 VPN 权限"
        "establishing_vpn" -> "建立 VPN"
        "recovering_vpn" -> "恢复 VPN"
        "connected" -> "已连接"
        "disconnecting" -> "断开中"
        "error" -> "连接错误"
        else -> "未知连接状态"
    }
    "vpn" -> when (event.state) {
        "idle" -> "VPN 空闲"
        "preparing" -> "准备 VPN"
        "attaching" -> "附加 VPN"
        "starting" -> "启动 VPN"
        "active" -> "VPN 运行中"
        "stopping" -> "停止 VPN"
        "stopped" -> "VPN 已停止"
        "error" -> "VPN 错误"
        "diagnostic" -> "VPN 数据面"
        "recovering" -> "VPN 恢复中"
        "waitingForNetwork" -> "VPN 等待网络"
        "waitingForAuthentication" -> "VPN 等待前台登录"
        "alwaysOnDisconnectBlocked" -> "VPN 由 Always-on 管理"
        else -> "未知 VPN 状态"
    }
    "service" -> when (event.state) {
        "idle" -> "服务空闲"
        "preparing" -> "服务准备中"
        "attaching" -> "服务附加中"
        "starting" -> "服务启动中"
        "active" -> "服务运行中"
        "stopping" -> "服务停止中"
        "stopped" -> "服务已停止"
        "error" -> "服务错误"
        "diagnostic" -> "服务诊断"
        "recovering" -> "服务恢复中"
        "waitingForNetwork" -> "服务等待网络"
        "waitingForAuthentication" -> "服务等待前台登录"
        "alwaysOnDisconnectBlocked" -> "服务由 Always-on 管理"
        "sessionRestore" -> "服务恢复会话"
        else -> "未知服务状态"
    }
    "authRecovery" -> when (event.state) {
        "reusable_result" -> "进程内认证结果"
        "persisted_session" -> "已保存登录状态"
        "persisted_session_authenticated" -> "已保存登录状态已认证"
        "persisted_session_stale" -> "已保存登录状态需重新认证"
        "saved_credentials" -> "已保存密码"
        "server_challenge" -> "服务端挑战"
        else -> "未知认证恢复状态"
    }
    else -> "未知状态"
}

internal fun diagnosticCategoryLabel(category: String): String = when (category) {
    "connection" -> "连接"
    "vpn" -> "VPN"
    "service" -> "服务"
    "authRecovery" -> "认证恢复"
    else -> "未知"
}

internal fun formatDiagnosticPreviewLine(group: DiagnosticEventGroup): String = buildString {
    val event = group.event
    append(formatDiagnosticPreviewTime(event.timestampMillis))
    append("  ")
    append(event.category)
    append('/')
    append(event.state.ifBlank { "unknown" })
    event.code.takeIf(String::isNotBlank)?.let {
        append("  code=")
        append(it)
    }
    event.stage.takeIf(String::isNotBlank)?.let {
        append("  stage=")
        append(it)
    }
    event.cause.takeIf(String::isNotBlank)?.let {
        append("  cause=")
        append(it)
    }
    event.durationMillis.takeIf { it > 0 }?.let {
        append("  durationMs=")
        append(it)
    }
    if (group.occurrences > 1) {
        append("  ×")
        append(group.occurrences)
    }
}

private fun formatDiagnosticPreviewTime(timestampMillis: Long): String = runCatching {
    java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.ofEpochMilli(timestampMillis.coerceAtLeast(0)))
}.getOrDefault("时间未知")

internal fun formatDiagnosticDisplayTime(timestampMillis: Long): String = runCatching {
    java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        .withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.ofEpochMilli(timestampMillis.coerceAtLeast(0)))
}.getOrDefault("时间未知")

private fun sameDiagnosticOccurrence(
    first: RedactedDiagnosticEvent,
    second: RedactedDiagnosticEvent,
): Boolean = first.category == second.category &&
    first.state == second.state &&
    first.code == second.code &&
    first.stage == second.stage &&
    first.cause == second.cause &&
    first.durationMillis == second.durationMillis

/**
 * Formats the only text copied by the diagnostics Activity. It formats already
 * redacted structured values rather than reading Android's system log buffer.
 */
internal fun formatRedactedDiagnosticReport(
    environment: DiagnosticReportEnvironment,
    events: List<RedactedDiagnosticEvent>,
    generatedAtMillis: Long = System.currentTimeMillis(),
): String {
    val reportEvents = events.map(RedactedDiagnosticEvent::redacted).takeLast(MAX_REDACTED_DIAGNOSTIC_EVENTS)
    return buildString {
        appendLine("ZJU Connect 诊断报告")
        appendLine("生成时间（UTC）：${Instant.ofEpochMilli(generatedAtMillis.coerceAtLeast(0))}")
        appendLine("应用：${publicDeviceValue(environment.appVersion)}")
        appendLine(
            "系统：Android ${publicDeviceValue(environment.androidRelease)} " +
                "(API ${environment.apiLevel.coerceAtLeast(0)})",
        )
        appendLine("设备：${publicDeviceValue(environment.manufacturer)} ${publicDeviceValue(environment.model)}")
        appendLine("记录：${reportEvents.size} 条")
        appendLine("说明：仅包含白名单状态、认证阶段、稳定原因码、耗时和数据面计数器；不含认证数据、网络端点或 Logcat。")
        appendLine()

        if (reportEvents.isEmpty()) {
            appendLine("（暂无诊断记录）")
        } else {
            reportEvents.forEach { event ->
                append(Instant.ofEpochMilli(event.timestampMillis).toString())
                append(" category=${event.category}")
                event.state.takeIf(String::isNotBlank)?.let { append(" state=$it") }
                event.code.takeIf(String::isNotBlank)?.let { append(" code=$it") }
                event.stage.takeIf(String::isNotBlank)?.let { append(" stage=$it") }
                event.cause.takeIf(String::isNotBlank)?.let { append(" cause=$it") }
                event.durationMillis.takeIf { it > 0 }?.let { append(" durationMs=$it") }
                event.counters?.let { counters ->
                    append(
                        " counters=" +
                            "tunRead=${counters.tunReadPackets}/${counters.tunReadBytes} " +
                            "forwardable=${counters.forwardablePackets} " +
                            "filtered=${counters.filteredPackets} " +
                            "l3Write=${counters.l3WriteSuccesses}/${counters.l3WriteAttempts} " +
                            "resourceDrops=${counters.resourceDrops} " +
                            "l3Read=${counters.l3ReadPackets}/${counters.l3ReadBytes} " +
                            "l3Invalid=${counters.l3InvalidPackets} " +
                            "tunWrite=${counters.tunWriteSuccesses}/${counters.tunWriteAttempts}/" +
                            counters.tunWriteBytes,
                    )
                }
                appendLine()
            }
        }
    }
}

/**
 * Small process-local owner for the persisted ring buffer. The single writer
 * avoids a logging framework while keeping foreground-service and UI writes
 * ordered and atomic.
 */
internal object RedactedDiagnostics {
    private var store: RedactedDiagnosticsStore? = null

    fun observe(context: Context): StateFlow<RedactedDiagnosticsSnapshot> = storeFor(context).snapshot

    fun clear(context: Context) {
        storeFor(context).clear()
    }

    fun recordConnectionState(
        context: Context,
        phase: ConnectionPhase,
        code: String,
        stage: String = "",
        cause: String = "",
        durationMillis: Long = 0,
    ) {
        storeFor(context).record(
            RedactedDiagnosticEvent(
                timestampMillis = System.currentTimeMillis(),
                category = "connection",
                state = phase.name.lowercase(),
                code = code,
                stage = stage,
                cause = cause,
                durationMillis = durationMillis,
            ),
        )
    }

    fun recordVpnEvent(context: Context, event: GoVpnEvent) {
        storeFor(context).record(
            RedactedDiagnosticEvent(
                timestampMillis = System.currentTimeMillis(),
                category = "vpn",
                state = event.state,
                code = event.code,
                stage = event.stage,
                cause = event.cause,
                counters = event.diagnostics?.toRedactedCounters(),
            ),
        )
    }

    fun recordVpnServiceState(context: Context, state: String, code: String = "") {
        storeFor(context).record(
            RedactedDiagnosticEvent(
                timestampMillis = System.currentTimeMillis(),
                category = "service",
                state = state,
                code = code,
            ),
        )
    }

    fun recordAuthenticationRecovery(
        context: Context,
        source: AuthenticationRecoverySource,
        outcome: AuthenticationRecoveryOutcome,
        cause: AuthenticationInvalidationCause? = null,
    ) {
        storeFor(context).record(
            RedactedDiagnosticEvent(
                timestampMillis = System.currentTimeMillis(),
                category = "authRecovery",
                state = source.diagnosticState,
                code = outcome.diagnosticCode,
                cause = cause?.diagnosticCause.orEmpty(),
            ),
        )
    }

    private fun storeFor(context: Context): RedactedDiagnosticsStore = synchronized(this) {
        store ?: RedactedDiagnosticsStore(context.applicationContext).also { store = it }
    }
}

private class RedactedDiagnosticsStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, DIAGNOSTIC_FILE_NAME))
    private val writer = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private val ring = RedactedDiagnosticRingBuffer()
    private var clearRequestedBeforeLoad = false
    private var hasLoaded = false
    private val mutableSnapshot = MutableStateFlow(RedactedDiagnosticsSnapshot())

    val snapshot: StateFlow<RedactedDiagnosticsSnapshot> = mutableSnapshot.asStateFlow()

    init {
        writer.execute(::loadPersistedEvents)
    }

    fun record(event: RedactedDiagnosticEvent) {
        synchronized(lock) {
            ring.append(event)
            publishLocked(hasLoaded)
        }
        writer.execute(::persistCurrentEvents)
    }

    fun clear() {
        synchronized(lock) {
            clearRequestedBeforeLoad = true
            ring.clear()
            hasLoaded = true
            publishLocked(loaded = true)
        }
        writer.execute { runCatching(file::delete) }
    }

    private fun loadPersistedEvents() {
        val persisted = synchronized(lock) {
            if (clearRequestedBeforeLoad) emptyList() else readPersistedEvents()
        }
        synchronized(lock) {
            if (!clearRequestedBeforeLoad) {
                ring.replace(persisted + ring.snapshot())
            }
            hasLoaded = true
            publishLocked(loaded = true)
        }
        persistCurrentEvents()
    }

    private fun publishLocked(loaded: Boolean) {
        val bounded = trimToByteLimit(ring.snapshot())
        if (bounded != ring.snapshot()) {
            ring.replace(bounded)
        }
        mutableSnapshot.value = RedactedDiagnosticsSnapshot(loaded = loaded, events = ring.snapshot())
    }

    private fun readPersistedEvents(): List<RedactedDiagnosticEvent> = runCatching {
        val bytes = file.readFully()
        try {
            val root = JSONObject(String(bytes, StandardCharsets.UTF_8))
            if (root.optInt("version", 0) != DIAGNOSTIC_FILE_VERSION) return@runCatching emptyList()
            val values = root.optJSONArray("events") ?: return@runCatching emptyList()
            buildList {
                for (index in 0 until values.length()) {
                    values.optJSONObject(index)?.let(::eventFromJson)?.let(::add)
                }
            }
        } finally {
            bytes.fill(0)
        }
    }.getOrElse { emptyList() }

    private fun persistCurrentEvents() {
        val events = synchronized(lock) { trimToByteLimit(ring.snapshot()) }
        val encoded = encodeEvents(events)
        runCatching {
            val output = file.startWrite()
            try {
                output.write(encoded)
                file.finishWrite(output)
            } catch (error: Throwable) {
                file.failWrite(output)
                throw error
            }
        }
        encoded.fill(0)
    }
}

private fun GoVpnDiagnostics.toRedactedCounters(): RedactedDiagnosticCounters = RedactedDiagnosticCounters(
    tunReadPackets = tunReadPackets,
    tunReadBytes = tunReadBytes,
    forwardablePackets = forwardablePackets,
    filteredPackets = filteredPackets,
    l3WriteAttempts = l3WriteAttempts,
    l3WriteSuccesses = l3WriteSuccesses,
    resourceDrops = resourceDrops,
    l3ReadPackets = l3ReadPackets,
    l3ReadBytes = l3ReadBytes,
    l3InvalidPackets = l3InvalidPackets,
    tunWriteAttempts = tunWriteAttempts,
    tunWriteSuccesses = tunWriteSuccesses,
    tunWriteBytes = tunWriteBytes,
)

private fun encodeEvents(events: List<RedactedDiagnosticEvent>): ByteArray = JSONObject()
    .put("version", DIAGNOSTIC_FILE_VERSION)
    .put(
        "events",
        JSONArray().also { values -> events.forEach { values.put(it.redacted().toJson()) } },
    )
    .toString()
    .toByteArray(StandardCharsets.UTF_8)

private fun eventFromJson(value: JSONObject): RedactedDiagnosticEvent? {
    val timestamp = value.optLong("timestampMillis", -1)
    if (timestamp < 0) return null
    return RedactedDiagnosticEvent(
        timestampMillis = timestamp,
        category = value.optString("category"),
        state = value.optString("state"),
        code = value.optString("code"),
        stage = value.optString("stage"),
        cause = value.optString("cause"),
        durationMillis = value.optLong("durationMs", 0),
        counters = value.optJSONObject("counters")?.toCounters(),
    ).redacted()
}

private fun RedactedDiagnosticEvent.toJson(): JSONObject = JSONObject().apply {
    put("timestampMillis", timestampMillis)
    put("category", category)
    put("state", state)
    put("code", code)
    put("stage", stage)
    put("cause", cause)
    put("durationMs", durationMillis)
    counters?.let { put("counters", it.toJson()) }
}

private fun RedactedDiagnosticCounters.toJson(): JSONObject = JSONObject().apply {
    put("tunReadPackets", tunReadPackets)
    put("tunReadBytes", tunReadBytes)
    put("forwardablePackets", forwardablePackets)
    put("filteredPackets", filteredPackets)
    put("l3WriteAttempts", l3WriteAttempts)
    put("l3WriteSuccesses", l3WriteSuccesses)
    put("resourceDrops", resourceDrops)
    put("l3ReadPackets", l3ReadPackets)
    put("l3ReadBytes", l3ReadBytes)
    put("l3InvalidPackets", l3InvalidPackets)
    put("tunWriteAttempts", tunWriteAttempts)
    put("tunWriteSuccesses", tunWriteSuccesses)
    put("tunWriteBytes", tunWriteBytes)
}

private fun JSONObject.toCounters(): RedactedDiagnosticCounters = RedactedDiagnosticCounters(
    tunReadPackets = optLong("tunReadPackets", 0),
    tunReadBytes = optLong("tunReadBytes", 0),
    forwardablePackets = optLong("forwardablePackets", 0),
    filteredPackets = optLong("filteredPackets", 0),
    l3WriteAttempts = optLong("l3WriteAttempts", 0),
    l3WriteSuccesses = optLong("l3WriteSuccesses", 0),
    resourceDrops = optLong("resourceDrops", 0),
    l3ReadPackets = optLong("l3ReadPackets", 0),
    l3ReadBytes = optLong("l3ReadBytes", 0),
    l3InvalidPackets = optLong("l3InvalidPackets", 0),
    tunWriteAttempts = optLong("tunWriteAttempts", 0),
    tunWriteSuccesses = optLong("tunWriteSuccesses", 0),
    tunWriteBytes = optLong("tunWriteBytes", 0),
).redacted()

private fun trimToByteLimit(events: List<RedactedDiagnosticEvent>): List<RedactedDiagnosticEvent> {
    var bounded = events.takeLast(MAX_REDACTED_DIAGNOSTIC_EVENTS)
    while (bounded.isNotEmpty() && encodeEvents(bounded).size > MAX_REDACTED_DIAGNOSTIC_BYTES) {
        bounded = bounded.drop(1)
    }
    return bounded
}

private fun redactState(category: String, value: String): String = when (category) {
    "connection" -> value.takeIf { it in SAFE_CONNECTION_STATES } ?: "unknown"
    "vpn", "service" -> value.takeIf { it in SAFE_VPN_STATES } ?: "unknown"
    "authRecovery" -> value.takeIf { it in SAFE_AUTH_RECOVERY_STATES } ?: "unknown"
    else -> "unknown"
}

private fun redactCode(value: String): String = when {
    value.isBlank() -> ""
    value in SAFE_DIAGNOSTIC_CODES -> value
    else -> "unknown"
}

private fun redactStage(value: String): String = when {
    value.isBlank() -> ""
    value in setOf(
        "auth",
        "auth.config",
        "auth.session_restore",
        "auth.select_method",
        "auth.credentials",
        "auth.phone",
        "auth.sms",
        "auth.captcha",
        "auth.token",
    ) -> value
    value.startsWith("auth") -> "auth"
    value.startsWith("prepare") -> "prepare"
    value.startsWith("tun") -> "tun"
    value.startsWith("dataplane") -> "dataplane"
    value.startsWith("vpn") -> "vpn"
    else -> "other"
}

private fun publicDeviceValue(value: String): String = value
    .replace(Regex("[\\r\\n\\t]"), " ")
    .trim()
    .take(80)
    .ifBlank { "unknown" }
