package cn.zju.connect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import cn.zju.connect.gocore.core.SocketProtector
import java.net.Inet4Address
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val REAL_VPN_CHANNEL = "zju_connect_real_vpn"
private const val REAL_VPN_NOTIFICATION_ID = 1002
private const val REAL_VPN_LOG_TAG = "ZjuConnectRealVpn"
private const val TUN_ESTABLISH_TIMEOUT_SECONDS = 20L
private const val UNDERLAY_RECOVERY_DEBOUNCE_MILLIS = 1_500L

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
    private val recoveryLock = Any()
    private val lifecycle = RealVpnLifecycle()
    private val recoveryCoordinator = RealVpnRecoveryCoordinator()
    private lateinit var underlayNetworkMonitor: UnderlayNetworkMonitor
    private var underlayMonitorFailure: Throwable? = null
    private var recoveryDebounce: ScheduledFuture<*>? = null
    private var publishedRecoveryPresentation = RealVpnRecoveryPresentation.NONE

    private val socketProtector = object : SocketProtector {
        override fun protect(socketFd: Long): Boolean = this@RealVpnService.protect(socketFd.toInt())
    }

    private val goListener: (GoVpnEvent) -> Unit = { event ->
        RedactedDiagnostics.recordVpnEvent(applicationContext, event)
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
                terminateRecovery()
                publishFailure("goCallback", failure)
                requestStop()
            } else {
                Log.i(REAL_VPN_LOG_TAG, "ignored bridge error after user stop")
            }
        } else if (event.state != "active" && isRecoveryInProgress()) {
            Log.i(REAL_VPN_LOG_TAG, "ignored intentional recovery ${event.state} callback")
        } else {
            val acceptsProgress = synchronized(stateLock) { lifecycle.acceptsProgress() }
            if (acceptsProgress) {
                RealVpnStateStore.update(event)
                if (event.state == "active") {
                    updateForegroundNotification("ZJU aTrust VPN is active")
                    val commands = synchronized(recoveryLock) {
                        recoveryCoordinator.onSessionActive(underlaySnapshot())
                    }
                    handleRecoveryCommands(commands)
                }
            } else {
                Log.i(REAL_VPN_LOG_TAG, "ignored bridge progress after terminal transition")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        underlayNetworkMonitor = UnderlayNetworkMonitor(applicationContext, ::onUnderlayNetworkChanged)
        runCatching { underlayNetworkMonitor.start() }
            .onFailure { error ->
                underlayMonitorFailure = error
                Log.e(REAL_VPN_LOG_TAG, "unable to register underlay network monitor")
            }
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
        terminateRecovery()
        if (::underlayNetworkMonitor.isInitialized) {
            underlayNetworkMonitor.stop()
        }
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
        terminateRecovery()
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

        val canStart = synchronized(recoveryLock) {
            recoveryCoordinator.beginSession(underlaySnapshot())
        }
        if (!canStart) {
            synchronized(stateLock) { lifecycle.beginCleanup() }
            publishRecoveryPresentation()
            return
        }

        val recovering = synchronized(recoveryLock) {
            recoveryCoordinator.presentation != RealVpnRecoveryPresentation.NONE
        }
        if (recovering) {
            publishRecoveryPresentation()
        } else {
            setStatus("preparing", "Preparing the authenticated aTrust VPN")
        }
        var detachedTunFd: Int? = null
        try {
            // Android requires a foreground service to publish its notification
            // before doing network work. PrepareRealVpn performs TLS requests
            // and resource parsing, so doing this afterwards can make Android
            // kill the service before the TUN is established.
            underlayMonitorFailure?.let {
                throw RealVpnStartFailure(
                    code = "networkMonitorUnavailable",
                    stage = "underlay.monitor",
                    message = "Android could not monitor the underlying network",
                )
            }
            startForegroundCompat(
                if (recovering) "Network changed; reconnecting VPN" else "ZJU aTrust VPN is active",
            )
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                builder.excludeCurrentUnderlaySubnets()
            }

            if (!recovering) {
                setStatus("attaching", "Establishing the Android VPN interface")
            }
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
                terminateRecovery()
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

    private fun onUnderlayNetworkChanged(snapshot: UnderlayNetworkSnapshot) {
        val commands = synchronized(recoveryLock) {
            recoveryCoordinator.onNetworkChanged(snapshot)
        }
        handleRecoveryCommands(commands)
    }

    private fun handleRecoveryCommands(commands: List<RealVpnRecoveryCommand>) {
        publishRecoveryPresentation()
        commands.forEach { command ->
            when (command) {
                RealVpnRecoveryCommand.CancelDebounce -> cancelRecoveryDebounce()
                is RealVpnRecoveryCommand.ScheduleDebounce -> scheduleRecoveryDebounce(command.revision)
                RealVpnRecoveryCommand.StopSession -> requestRecoveryStop()
                RealVpnRecoveryCommand.StartSession -> requestRecoveryStart()
            }
        }
    }

    private fun scheduleRecoveryDebounce(revision: Long) {
        if (watchdogExecutor.isShutdown) return
        synchronized(recoveryLock) {
            recoveryDebounce?.cancel(false)
            recoveryDebounce = watchdogExecutor.schedule(
                {
                    val commands = synchronized(recoveryLock) {
                        recoveryDebounce = null
                        recoveryCoordinator.onDebounceElapsed(revision)
                    }
                    handleRecoveryCommands(commands)
                },
                UNDERLAY_RECOVERY_DEBOUNCE_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun cancelRecoveryDebounce() {
        synchronized(recoveryLock) {
            recoveryDebounce?.cancel(false)
            recoveryDebounce = null
        }
    }

    private fun requestRecoveryStop() {
        if (!cleanupExecutor.isShutdown) {
            cleanupExecutor.execute(::stopForRecoveryInternal)
        }
    }

    private fun stopForRecoveryInternal() {
        if (!isStoppingForRecovery()) return
        synchronized(stateLock) { lifecycle.beginCleanup() }
        goCoreBridge.stopRealVpn()
        val commands = synchronized(recoveryLock) {
            if (recoveryCoordinator.isStoppingForRecovery) {
                recoveryCoordinator.onRecoveryStopCompleted(underlaySnapshot())
            } else {
                emptyList()
            }
        }
        handleRecoveryCommands(commands)
    }

    private fun requestRecoveryStart() {
        if (!executor.isShutdown) {
            executor.execute(::startInternal)
        }
    }

    private fun terminateRecovery() {
        val commands = synchronized(recoveryLock) { recoveryCoordinator.terminate() }
        handleRecoveryCommands(commands)
    }

    private fun isStoppingForRecovery(): Boolean = synchronized(recoveryLock) {
        recoveryCoordinator.isStoppingForRecovery
    }

    private fun isRecoveryInProgress(): Boolean = synchronized(recoveryLock) {
        recoveryCoordinator.presentation != RealVpnRecoveryPresentation.NONE
    }

    private fun underlaySnapshot(): UnderlayNetworkSnapshot =
        if (::underlayNetworkMonitor.isInitialized) {
            underlayNetworkMonitor.snapshot()
        } else {
            UnderlayNetworkSnapshot()
        }

    private fun publishRecoveryPresentation() {
        val presentation = synchronized(recoveryLock) { recoveryCoordinator.presentation }
        if (presentation == publishedRecoveryPresentation) return
        publishedRecoveryPresentation = presentation
        when (presentation) {
            RealVpnRecoveryPresentation.NONE -> Unit
            RealVpnRecoveryPresentation.RECOVERING -> {
                setStatus("recovering", "Network changed; reconnecting VPN")
                updateForegroundNotification("Network changed; reconnecting VPN")
            }
            RealVpnRecoveryPresentation.WAITING_FOR_NETWORK -> {
                setStatus("waitingForNetwork", "Waiting for an underlying network")
                updateForegroundNotification("Waiting for an underlying network")
            }
        }
    }

    private fun acceptsStartProgress(): Boolean = synchronized(stateLock) { lifecycle.acceptsProgress() }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Suppress("DEPRECATION")
    private fun Builder.excludeCurrentUnderlaySubnets() {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val prefixes = connectivity.allNetworks
            .asSequence()
            .filter { network ->
                connectivity.getNetworkCapabilities(network)
                    ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) != true
            }
            .mapNotNull(connectivity::getLinkProperties)
            .flatMap { properties -> properties.linkAddresses.asSequence() }
            .filter { link -> link.address is Inet4Address && link.prefixLength in 1..31 }
            .map { link -> IpPrefix(link.address, link.prefixLength) }
            .distinct()
            .toList()
        prefixes.forEach(::excludeRoute)
        Log.i(REAL_VPN_LOG_TAG, "phase=tun.routes.exclude-underlay prefixes=${prefixes.size}")
    }

    private fun closeDetachedTunFd(fd: Int?) {
        fd?.let { descriptor ->
            runCatching { android.os.ParcelFileDescriptor.adoptFd(descriptor).close() }
        }
    }

    private fun setStatus(state: String, message: String) {
        Log.i(REAL_VPN_LOG_TAG, "service state=$state")
        RedactedDiagnostics.recordVpnServiceState(applicationContext, state)
        RealVpnStateStore.setStatus(state, message)
    }

    private fun publishFailure(origin: String, failure: RealVpnFailure) {
        Log.e(REAL_VPN_LOG_TAG, "terminal failure origin=$origin code=${failure.code}")
        RedactedDiagnostics.recordVpnServiceState(applicationContext, "error", failure.code)
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

    private fun startForegroundCompat(contentText: String) {
        val notification = Notification.Builder(this, REAL_VPN_CHANNEL)
            .setContentTitle("ZJU Connect")
            .setContentText(contentText)
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

    private fun updateForegroundNotification(contentText: String) {
        // Re-post through the already-running foreground service instead of
        // NotificationManager.notify(), which would require POST_NOTIFICATIONS
        // on Android 13+ and is not needed for the VPN lifecycle notification.
        startForegroundCompat(contentText)
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
                    "len=${packet.length} dataLen=${packet.dataLength} " +
                    "tcpFlags=0x${packet.tcpFlags.toString(16)} " +
                    "seq=${packet.tcpSequence} ack=${packet.tcpAcknowledgment} " +
                    "window=${packet.tcpWindow} " +
                    "checksums=${packet.ipChecksum}/${packet.transportChecksum} " +
                    "valid=${packet.valid} truncated=${packet.truncated}",
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
    private val mutableState = MutableStateFlow(RealVpnUiState())
    val state: StateFlow<RealVpnUiState> = mutableState.asStateFlow()

    fun update(event: GoVpnEvent) {
        mutableState.update {
            it.copy(
                state = event.state,
                code = event.code,
                message = event.message,
            )
        }
    }

    fun setStatus(nextState: String, message: String) {
        mutableState.update { it.copy(state = nextState, code = "", message = message) }
    }

    fun setError(code: String, message: String) {
        mutableState.update { it.copy(state = "error", code = code, message = message) }
    }

    fun reset() {
        mutableState.value = RealVpnUiState()
    }
}
