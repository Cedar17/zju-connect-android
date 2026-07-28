package cn.zju.connect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.zju.connect.gocore.core.SocketProtector
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val REAL_VPN_CHANNEL = "zju_connect_real_vpn"
private const val REAL_VPN_NOTIFICATION_ID = 1002
private const val REAL_VPN_LOG_TAG = "ZjuConnectRealVpn"
private const val TUN_ESTABLISH_TIMEOUT_SECONDS = 20L

/** Owns the production Android TUN and the authenticated zju-connect client. */
class RealVpnService : VpnService() {
    companion object {
        const val ACTION_START = "cn.zju.connect.action.START_REAL_VPN"
        const val ACTION_STOP = "cn.zju.connect.action.STOP_REAL_VPN"
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cleanupExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val watchdogExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val goCoreBridge = GoCoreBridge()
    private val stateLock = Any()
    private val lifecycle = RealVpnLifecycle()

    private val socketProtector = object : SocketProtector {
        override fun protect(socketFd: Long): Boolean = this@RealVpnService.protect(socketFd.toInt())
    }

    private val goListener: (GoVpnEvent) -> Unit = { event ->
        Log.i(
            REAL_VPN_LOG_TAG,
            "bridge state=${event.state} code=${event.code.ifBlank { "none" }} " +
                "stage=${event.stage.ifBlank { "none" }} cause=${event.cause.ifBlank { "none" }}",
        )
        realVpnDiagnosticLog(event)?.let { Log.i(REAL_VPN_LOG_TAG, it) }
        if (event.state == "diagnostic") {
            // Diagnostic observations must never replace the user-visible
            // lifecycle state (for example, active) in the Compose store.
            Unit
        } else if (event.state == "error") {
            val failure = synchronized(stateLock) {
                lifecycle.recordFailure(event.code, realVpnErrorMessage(event))
            }
            if (failure != null) {
                publishFailure("goCallback", failure)
                requestStop()
            } else {
                Log.i(REAL_VPN_LOG_TAG, "ignored bridge error after user stop")
            }
        } else {
            val acceptsProgress = synchronized(stateLock) { lifecycle.acceptsProgress() }
            if (acceptsProgress) {
                RealVpnStateStore.update(event)
            } else {
                Log.i(REAL_VPN_LOG_TAG, "ignored bridge progress after terminal transition")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> requestStart()
            ACTION_STOP -> requestStop(userInitiated = true)
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        val failure = synchronized(stateLock) { lifecycle.recordRevocation() }
        failure?.let { publishFailure("onRevoke", it) }
        requestStop()
        super.onRevoke()
    }

    override fun onDestroy() {
        val failure = synchronized(stateLock) { lifecycle.recordUnexpectedDestruction() }
        if (failure != null) {
            publishFailure("onDestroy", failure)
        }
        stopInternal()
        executor.shutdownNow()
        cleanupExecutor.shutdownNow()
        watchdogExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun requestStart() {
        if (!executor.isShutdown) {
            executor.execute { startInternal() }
        }
    }

    private fun requestStop(userInitiated: Boolean = false) {
        synchronized(stateLock) {
            if (userInitiated) {
                lifecycle.requestUserStop()
            }
        }
        if (!cleanupExecutor.isShutdown) {
            cleanupExecutor.execute { stopInternal() }
        } else {
            stopInternal()
        }
    }

    private fun startInternal() {
        val started = synchronized(stateLock) { lifecycle.beginSession() }
        if (!started) {
            Log.i(REAL_VPN_LOG_TAG, "ignored duplicate start request")
            return
        }

        setStatus("preparing", "Preparing the authenticated aTrust VPN")
        var detachedTunFd: Int? = null
        try {
            // Android requires a foreground service to publish its notification
            // before doing network work. PrepareRealVpn performs TLS requests
            // and resource parsing, so doing this afterwards can make Android
            // kill the service before the TUN is established.
            startForegroundCompat()
            val config = goCoreBridge.prepareRealVpn()
            Log.i(
                REAL_VPN_LOG_TAG,
                "prepared state=${config.state} code=${config.code.ifBlank { "none" }} " +
                    "stage=${config.stage.ifBlank { "none" }} cause=${config.cause.ifBlank { "none" }} " +
                    "routes=${config.routes.size} mtu=${config.mtu}",
            )
            if (config.state == "error") {
                throw RealVpnStartFailure(
                    code = config.code.ifBlank { "vpnSetupFailed" },
                    stage = config.stage.ifBlank { "prepare" },
                    message = config.message.ifBlank { "The aTrust VPN configuration is unavailable" },
                )
            }
            if (config.address.isBlank() || config.routes.isEmpty()) {
                throw RealVpnStartFailure(
                    code = "vpnConfigurationUnavailable",
                    stage = "prepare.configuration",
                    message = "The aTrust VPN configuration is incomplete",
                )
            }
            if (!acceptsStartProgress()) {
                goCoreBridge.stopRealVpn()
                return
            }

            val builder = Builder()
                .setSession("ZJU Connect")
                .setMtu(config.mtu.coerceIn(576, 1500))
                .setBlocking(true)
                .addAddress(config.address, 32)

            config.routes.forEach { route ->
                builder.addRoute(route.address, route.prefixLength)
            }

            setStatus("attaching", "Establishing the Android VPN interface")
            Log.i(REAL_VPN_LOG_TAG, "phase=tun.establish.begin routes=${config.routes.size}")
            val watchdog = scheduleTunEstablishWatchdog()
            val tun = try {
                builder.establish()
                    ?: throw RealVpnStartFailure(
                        code = "tunEstablishFailed",
                        stage = "tun.establish",
                        message = "Android refused to establish the VPN interface",
                    )
            } finally {
                watchdog.cancel(false)
            }
            Log.i(REAL_VPN_LOG_TAG, "phase=tun.establish.complete")
            if (!acceptsStartProgress()) {
                Log.i(REAL_VPN_LOG_TAG, "phase=tun.establish.aborted")
                tun.close()
                return
            }
            val tunFd = tun.detachFd()
            Log.i(REAL_VPN_LOG_TAG, "phase=tun.detach.complete")
            detachedTunFd = tunFd
            tun.close()

            if (!acceptsStartProgress()) {
                closeDetachedTunFd(detachedTunFd)
                detachedTunFd = null
                return
            }
            Log.i(REAL_VPN_LOG_TAG, "phase=go.attach.begin")
            goCoreBridge.startRealVpn(tunFd.toLong(), socketProtector, goListener)
            Log.i(REAL_VPN_LOG_TAG, "phase=go.attach.complete")
            detachedTunFd = null
        } catch (error: Throwable) {
            closeDetachedTunFd(detachedTunFd)
            detachedTunFd = null
            val failure = when (error) {
                is RealVpnStartFailure -> RealVpnFailure(error.code, error.message ?: "Unable to start the real aTrust VPN")
                else -> RealVpnFailure(
                    code = "vpnStartFailed",
                    message = error.message ?: "Unable to start the real aTrust VPN",
                )
            }
            val retainedFailure = synchronized(stateLock) {
                lifecycle.recordFailure(failure.code, failure.message)
            }
            if (retainedFailure != null) {
                publishFailure("startInternal", retainedFailure)
            } else {
                Log.i(REAL_VPN_LOG_TAG, "ignored startup failure after user stop")
            }
            stopInternal()
        }
    }

    private fun scheduleTunEstablishWatchdog(): ScheduledFuture<*> =
        watchdogExecutor.schedule(
            {
                val failure = synchronized(stateLock) {
                    lifecycle.recordFailure(
                        code = "tunEstablishTimeout",
                        message = "Android VPN interface setup timed out",
                    )
                }
                if (failure != null) {
                    publishFailure("tunEstablishWatchdog", failure)
                    requestStop()
                }
            },
            TUN_ESTABLISH_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )

    private fun stopInternal() {
        val shouldStop = synchronized(stateLock) { lifecycle.beginCleanup() }
        val outcomeBeforeCleanup = synchronized(stateLock) { lifecycle.terminalOutcome() }

        if (shouldStop && outcomeBeforeCleanup == null) {
            setStatus("stopping", "Stopping the real aTrust VPN")
        }
        goCoreBridge.stopRealVpn()
        stopForegroundCompat()
        when (val outcome = synchronized(stateLock) { lifecycle.terminalOutcome() }) {
            is RealVpnTerminalOutcome.Error -> publishFailure("stopInternal", outcome.failure)
            RealVpnTerminalOutcome.Stopped -> setStatus("stopped", "Real aTrust VPN is stopped")
            null -> Unit
        }
        stopSelf()
    }

    private fun acceptsStartProgress(): Boolean = synchronized(stateLock) { lifecycle.acceptsProgress() }

    private fun closeDetachedTunFd(fd: Int?) {
        fd?.let { descriptor ->
            runCatching { android.os.ParcelFileDescriptor.adoptFd(descriptor).close() }
        }
    }

    private fun setStatus(state: String, message: String) {
        Log.i(REAL_VPN_LOG_TAG, "service state=$state")
        RealVpnStateStore.setStatus(state, message)
    }

    private fun publishFailure(origin: String, failure: RealVpnFailure) {
        Log.e(REAL_VPN_LOG_TAG, "terminal failure origin=$origin code=${failure.code}")
        RealVpnStateStore.setError(failure.code, failure.message)
    }

    private class RealVpnStartFailure(
        val code: String,
        val stage: String,
        message: String,
    ) : IllegalStateException(message)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                REAL_VPN_CHANNEL,
                "ZJU Connect VPN",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat() {
        val notification = Notification.Builder(this, REAL_VPN_CHANNEL)
            .setContentTitle("ZJU Connect")
            .setContentText("ZJU aTrust VPN is active")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                REAL_VPN_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(REAL_VPN_NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}

/**
 * The bridge's raw I/O errors are intentionally not exposed. For the one
 * packet-injection boundary that currently needs device diagnosis, show only
 * the fixed, non-sensitive reason category in the existing error message.
 */
internal fun realVpnErrorMessage(event: GoVpnEvent): String {
    val message = event.message.ifBlank { "The real aTrust VPN stopped unexpectedly" }
    val cause = event.cause.takeIf { it in TUN_WRITE_DIAGNOSTIC_CAUSES } ?: return message
    return "$message (diagnostic: ${event.stage}/$cause)"
}

private val TUN_WRITE_DIAGNOSTIC_CAUSES = setOf(
    "fdClosed",
    "wouldBlock",
    "packetTooLarge",
    "invalidPacket",
    "tunUnavailable",
    "io",
)

internal fun realVpnDiagnosticLog(event: GoVpnEvent): String? {
    val diagnostics = event.diagnostics ?: return null
    return buildString {
        append(
            "dataplane counters " +
                "tunRead=${diagnostics.tunReadPackets}/${diagnostics.tunReadBytes} " +
                "forwardable=${diagnostics.forwardablePackets} filtered=${diagnostics.filteredPackets} " +
                "l3Write=${diagnostics.l3WriteSuccesses}/${diagnostics.l3WriteAttempts} " +
                "resourceDrops=${diagnostics.resourceDrops} " +
                "l3Read=${diagnostics.l3ReadPackets}/${diagnostics.l3ReadBytes} " +
                "l3Invalid=${diagnostics.l3InvalidPackets} " +
                "tunWrite=${diagnostics.tunWriteSuccesses}/${diagnostics.tunWriteAttempts}/" +
                diagnostics.tunWriteBytes,
        )
        event.packet?.let { packet ->
            append(
                " packet seq=${packet.sequence} direction=${packet.direction} " +
                    "ip=${packet.ipVersion} protocol=${packet.protocol} " +
                    "${packet.sourceIp}:${packet.sourcePort}->" +
                    "${packet.destinationIp}:${packet.destinationPort} " +
                    "len=${packet.length} valid=${packet.valid} truncated=${packet.truncated}",
            )
        }
    }
}

data class RealVpnUiState(
    val state: String = "idle",
    val code: String = "",
    val message: String = "Real VPN is idle",
)

object RealVpnStateStore {
    var state by mutableStateOf(RealVpnUiState())
        private set

    @Synchronized
    fun update(event: GoVpnEvent) {
        state = state.copy(
            state = event.state,
            code = event.code,
            message = event.message,
        )
    }

    @Synchronized
    fun setStatus(nextState: String, message: String) {
        state = state.copy(state = nextState, code = "", message = message)
    }

    @Synchronized
    fun setError(code: String, message: String) {
        state = state.copy(state = "error", code = code, message = message)
    }

    @Synchronized
    fun reset() {
        state = RealVpnUiState()
    }
}
