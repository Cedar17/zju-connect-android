package io.github.cedar17.zjuconnect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import io.github.cedar17.zjuconnect.gocore.core.SocketProtector
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

private const val VPN_STATUS_CHANNEL = "zju_connect_vpn_status"
private const val LEGACY_REAL_VPN_CHANNEL = "zju_connect_real_vpn"
private const val REAL_VPN_NOTIFICATION_ID = 1002
private const val ALWAYS_ON_GUIDANCE_NOTIFICATION_ID = 1003
private const val REAL_VPN_LOG_TAG = "ZjuConnectRealVpn"
private const val TUN_ESTABLISH_TIMEOUT_SECONDS = 20L
private const val UNDERLAY_RECOVERY_DEBOUNCE_MILLIS = 1_500L

/** Owns the production Android TUN and the authenticated zju-connect client. */
class RealVpnService : VpnService() {
    companion object {
        const val ACTION_START = "io.github.cedar17.zjuconnect.action.START_REAL_VPN"
        const val ACTION_STOP = "io.github.cedar17.zjuconnect.action.STOP_REAL_VPN"

        @Volatile
        private var runningInstance: RealVpnService? = null

        fun refreshNotificationIfRunning() {
            runningInstance?.refreshCurrentNotification()
        }

        /** Cancels only a background Always-on restore before UI auth begins. */
        fun prepareForForegroundAuthentication() {
            runningInstance?.handoffToForegroundAuthentication()
        }
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cleanupExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val watchdogExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val goCoreBridge = GoCoreBridge()
    private val stateLock = Any()
    private val recoveryLock = Any()
    private val lifecycle = RealVpnLifecycle()
    private val bridgeSessionTracker = RealVpnBridgeSessionTracker()
    private val recoveryCoordinator = RealVpnRecoveryCoordinator()
    private val alwaysOnRetryPolicy = AlwaysOnRestoreRetryPolicy()
    private lateinit var underlayNetworkMonitor: UnderlayNetworkMonitor
    private var underlayMonitorFailure: Throwable? = null
    @Volatile
    @StringRes
    private var currentNotificationTextRes = R.string.notification_connecting
    private var recoveryDebounce: ScheduledFuture<*>? = null
    private var l3RecoveryActive = false
    private var publishedRecoveryPresentation = RealVpnRecoveryPresentation.NONE
    private var activeStartMode = RealVpnStartMode.MANUAL
    private var serviceEntryOwner: ConnectionEntryOwner? = null
    @Volatile
    private var alwaysOnWaiting = false
    private val alwaysOnRestoreLock = Any()
    private var alwaysOnRestoreRevision = Long.MIN_VALUE
    private var alwaysOnRestoreInFlight = false
    private var alwaysOnRestoreFuture: ScheduledFuture<*>? = null
    private var alwaysOnSessionRestorer: AlwaysOnSessionRestorer? = null
    private var tileRestoreInFlight = false
    private var tileWaitingForAuthentication = false

    private val socketProtector = object : SocketProtector {
        override fun protect(socketFd: Long): Boolean = this@RealVpnService.protect(socketFd.toInt())
    }

    private fun goListener(generation: Long): (GoVpnEvent) -> Unit = listener@{ event ->
        if (!synchronized(stateLock) { bridgeSessionTracker.accepts(generation) }) {
            Log.i(
                REAL_VPN_LOG_TAG,
                "ignored stale bridge callback generation=$generation state=${event.state}",
            )
            return@listener
        }
        RedactedDiagnostics.recordVpnEvent(applicationContext, event)
        Log.i(
            REAL_VPN_LOG_TAG,
            "bridge state=${event.state} code=${event.code.ifBlank { "none" }} " +
                "stage=${event.stage.ifBlank { "none" }} cause=${event.cause.ifBlank { "none" }}",
        )
        realVpnDiagnosticLog(event)?.let { Log.i(REAL_VPN_LOG_TAG, it) }
        val eventKind = classifyRealVpnBridgeEvent(event)
        if (eventKind == RealVpnBridgeEventKind.DIAGNOSTIC) {
            // Diagnostic observations must never replace the user-visible
            // lifecycle state (for example, active) in the Compose store.
            Unit
        } else if (eventKind == RealVpnBridgeEventKind.TERMINAL_ERROR) {
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
        } else if (eventKind == RealVpnBridgeEventKind.L3_RECOVERING) {
            val acceptsProgress = synchronized(stateLock) { lifecycle.acceptsProgress() }
            if (acceptsProgress) {
                synchronized(recoveryLock) { l3RecoveryActive = true }
                publishRecoveryPresentation()
            }
        } else if (eventKind == RealVpnBridgeEventKind.L3_RECOVERED) {
            val acceptsProgress = synchronized(stateLock) { lifecycle.acceptsProgress() }
            if (acceptsProgress) {
                synchronized(recoveryLock) { l3RecoveryActive = false }
                publishRecoveryPresentation()
            }
        } else if (event.state != "active" && isUnderlayRecoveryInProgress()) {
            Log.i(REAL_VPN_LOG_TAG, "ignored intentional recovery ${event.state} callback")
        } else {
            val acceptsProgress = synchronized(stateLock) { lifecycle.acceptsProgress() }
            if (acceptsProgress) {
                RealVpnStateStore.update(event)
                QuickSettingsTileService.requestRefresh(applicationContext)
                if (event.state == "active") {
                    startForegroundCompat(R.string.notification_connected)
                    val commands = synchronized(recoveryLock) {
                        l3RecoveryActive = false
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
        runningInstance = this
        createNotificationChannel()
        underlayNetworkMonitor = UnderlayNetworkMonitor(applicationContext, ::onUnderlayNetworkChanged)
        runCatching { underlayNetworkMonitor.start() }
            .onFailure { error ->
                underlayMonitorFailure = error
                Log.e(REAL_VPN_LOG_TAG, "unable to register underlay network monitor")
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStop(userInitiated = true)
            return if (systemAlwaysOnEnabled()) START_STICKY else START_NOT_STICKY
        }
        val mode = classifyRealVpnStart(
            action = intent?.action,
            manualStartAction = ACTION_START,
            startSource = intent?.getStringExtra(REAL_VPN_EXTRA_START_SOURCE),
        )
        requestStart(mode)
        return when (realVpnRestartPolicy(mode)) {
            RealVpnRestartPolicy.START_STICKY -> START_STICKY
            RealVpnRestartPolicy.START_NOT_STICKY -> START_NOT_STICKY
        }
    }

    override fun onRevoke() {
        val failure = synchronized(stateLock) { lifecycle.recordRevocation() }
        failure?.let { publishFailure("onRevoke", it) }
        requestStop()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (runningInstance === this) {
            runningInstance = null
        }
        val failure = synchronized(stateLock) { lifecycle.recordUnexpectedDestruction() }
        cancelAlwaysOnRestore(forceBridgeCancellation = true)
        cancelTileRestore(forceBridgeCancellation = true)
        terminateRecovery()
        if (::underlayNetworkMonitor.isInitialized) {
            underlayNetworkMonitor.stop()
        }
        if (failure != null) {
            publishFailure("onDestroy", failure)
        }
        stopInternal()
        releaseServiceEntry()
        QuickSettingsTileService.requestRefresh(applicationContext)
        executor.shutdownNow()
        cleanupExecutor.shutdownNow()
        watchdogExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun requestStart(mode: RealVpnStartMode) {
        if (!executor.isShutdown) {
            executor.execute {
                when (mode) {
                    RealVpnStartMode.MANUAL -> {
                        if (alwaysOnWaiting) {
                            cancelAlwaysOnRestore(forceBridgeCancellation = false)
                            alwaysOnWaiting = false
                        }
                        cancelTileRestore(forceBridgeCancellation = true)
                        startPreparedInternal(RealVpnStartMode.MANUAL)
                    }
                    RealVpnStartMode.TILE -> beginTileInternal()
                    RealVpnStartMode.ALWAYS_ON -> beginAlwaysOnInternal()
                }
            }
        }
    }

    private fun requestStop(userInitiated: Boolean = false) {
        if (shouldBlockAlwaysOnDisconnect(userInitiated, systemAlwaysOnEnabled())) {
            publishAlwaysOnDisconnectBlocked()
            return
        }
        synchronized(stateLock) {
            if (userInitiated) {
                lifecycle.requestUserStop()
            }
        }
        cancelAlwaysOnRestore(forceBridgeCancellation = true)
        cancelTileRestore(forceBridgeCancellation = true)
        if (userInitiated) {
            releaseUnclaimedActivityEntry()
        }
        terminateRecovery()
        if (!cleanupExecutor.isShutdown) {
            cleanupExecutor.execute { stopInternal() }
        } else {
            stopInternal()
        }
    }

    private fun beginAlwaysOnInternal() {
        if (hasTileRestoreOrAuthenticationWaiting()) {
            cancelTileRestore(forceBridgeCancellation = true)
        }
        if (alwaysOnWaiting) {
            Log.i(REAL_VPN_LOG_TAG, "ignored duplicate Always-on start while restore is pending")
            return
        }
        if (synchronized(stateLock) { lifecycle.hasActiveSession() }) {
            Log.i(REAL_VPN_LOG_TAG, "ignored Always-on start while VPN session is active")
            return
        }
        alwaysOnWaiting = true
        startForegroundCompat(R.string.notification_connecting)
        setStatus("preparing")
        scheduleAlwaysOnRestoreForNetwork(
            snapshot = underlaySnapshot(),
            resetRevision = true,
        )
    }

    private fun beginTileInternal() {
        if (hasTileRestoreOrAuthenticationWaiting()) {
            Log.i(REAL_VPN_LOG_TAG, "ignored duplicate Tile start while restore is pending")
            return
        }
        if (alwaysOnWaiting) {
            cancelAlwaysOnRestore(forceBridgeCancellation = true)
            alwaysOnWaiting = false
        }
        if (synchronized(stateLock) { lifecycle.hasActiveSession() }) {
            Log.i(REAL_VPN_LOG_TAG, "ignored Tile start while VPN session is active")
            return
        }

        try {
            claimServiceEntry(ConnectionEntryOwner.TILE_SERVICE)
            startForegroundCompat(R.string.notification_connecting)
            setStatus("preparing")
            if (goCoreBridge.hasReusableAuthenticatedResult()) {
                startPreparedInternal(RealVpnStartMode.TILE)
                return
            }

            synchronized(alwaysOnRestoreLock) {
                tileRestoreInFlight = true
                tileWaitingForAuthentication = false
            }
            getAlwaysOnSessionRestorer().start { result ->
                if (!executor.isShutdown) {
                    executor.execute { handleTileSessionRestoreResult(result) }
                }
            }
        } catch (error: Throwable) {
            synchronized(alwaysOnRestoreLock) {
                tileRestoreInFlight = false
                tileWaitingForAuthentication = false
            }
            releaseServiceEntry(ConnectionEntryOwner.TILE_SERVICE)
            publishFailure(
                "beginTileInternal",
                RealVpnFailure(
                    code = "vpnStartDispatchFailed",
                    message = error.message ?: "Unable to start the VPN from Quick Settings",
                ),
            )
            stopInternal()
        }
    }

    private fun scheduleAlwaysOnRestoreForNetwork(
        snapshot: UnderlayNetworkSnapshot,
        resetRevision: Boolean,
    ) {
        if (!alwaysOnWaiting) return

        var cancelBridge = false
        if (underlayMonitorFailure != null || !snapshot.hasUsableNetwork) {
            synchronized(alwaysOnRestoreLock) {
                alwaysOnRestoreFuture?.cancel(false)
                alwaysOnRestoreFuture = null
                cancelBridge = alwaysOnRestoreInFlight
                alwaysOnRestoreInFlight = false
                alwaysOnRestoreRevision = snapshot.revision
                alwaysOnRetryPolicy.resetForRevision(snapshot.revision)
            }
            if (cancelBridge) {
                alwaysOnSessionRestorer?.cancel()
            } else {
                alwaysOnSessionRestorer?.invalidate()
            }
            enterAlwaysOnWaitingForNetwork()
            return
        }

        var delayMillis: Long? = null
        var exhausted = false
        synchronized(alwaysOnRestoreLock) {
            if (resetRevision || alwaysOnRestoreRevision != snapshot.revision) {
                alwaysOnRestoreFuture?.cancel(false)
                alwaysOnRestoreFuture = null
                cancelBridge = alwaysOnRestoreInFlight
                alwaysOnRestoreInFlight = false
                alwaysOnRestoreRevision = snapshot.revision
                alwaysOnRetryPolicy.resetForRevision(snapshot.revision)
            }
            if (!alwaysOnRestoreInFlight && alwaysOnRestoreFuture == null) {
                delayMillis = alwaysOnRetryPolicy.nextDelayFor(snapshot.revision)
                exhausted = delayMillis == null
                delayMillis?.let { delay ->
                    alwaysOnRestoreFuture = watchdogExecutor.schedule(
                        {
                            synchronized(alwaysOnRestoreLock) {
                                alwaysOnRestoreFuture = null
                                alwaysOnRestoreInFlight = true
                            }
                            if (!executor.isShutdown) {
                                executor.execute {
                                    runAlwaysOnSessionRestore(snapshot.revision)
                                }
                            }
                        },
                        delay,
                        TimeUnit.MILLISECONDS,
                    )
                }
            }
        }
        if (cancelBridge) {
            alwaysOnSessionRestorer?.cancel()
        } else if (delayMillis != null) {
            alwaysOnSessionRestorer?.invalidate()
        }
        if (exhausted) {
            enterAlwaysOnWaitingForNetwork()
        }
    }

    private fun runAlwaysOnSessionRestore(revision: Long) {
        if (!alwaysOnWaiting) return
        val snapshot = underlaySnapshot()
        if (snapshot.revision != revision) {
            scheduleAlwaysOnRestoreForNetwork(snapshot, resetRevision = true)
            return
        }
        if (underlayMonitorFailure != null || !snapshot.hasUsableNetwork) {
            scheduleAlwaysOnRestoreForNetwork(snapshot, resetRevision = false)
            return
        }
        val restorer = getAlwaysOnSessionRestorer()
        restorer.start { result ->
            if (!executor.isShutdown) {
                executor.execute {
                    handleAlwaysOnSessionRestoreResult(revision, result)
                }
            }
        }
    }

    private fun handleAlwaysOnSessionRestoreResult(
        revision: Long,
        result: AlwaysOnSessionRestoreResult,
    ) {
        if (result.code.isNotBlank()) {
            RedactedDiagnostics.recordVpnServiceState(
                applicationContext,
                state = "sessionRestore",
                code = result.code,
            )
        }
        synchronized(alwaysOnRestoreLock) {
            alwaysOnRestoreInFlight = false
        }
        if (!alwaysOnWaiting) return
        val snapshot = underlaySnapshot()
        if (snapshot.revision != revision) {
            scheduleAlwaysOnRestoreForNetwork(snapshot, resetRevision = true)
            return
        }
        when (result.outcome) {
            AlwaysOnSessionRestoreOutcome.Authenticated -> {
                alwaysOnWaiting = false
                synchronized(alwaysOnRestoreLock) {
                    alwaysOnRestoreFuture?.cancel(false)
                    alwaysOnRestoreFuture = null
                }
                startPreparedInternal(RealVpnStartMode.ALWAYS_ON)
            }
            AlwaysOnSessionRestoreOutcome.WaitingForUserAuthentication,
            AlwaysOnSessionRestoreOutcome.InvalidSession,
            -> enterAlwaysOnWaitingForAuthentication()
            AlwaysOnSessionRestoreOutcome.TransientFailure -> {
                if (!snapshot.hasUsableNetwork) {
                    enterAlwaysOnWaitingForNetwork()
                } else {
                    scheduleAlwaysOnRestoreForNetwork(snapshot, resetRevision = false)
                }
            }
        }
    }

    private fun enterAlwaysOnWaitingForNetwork() {
        alwaysOnWaiting = true
        setStatus("waitingForNetwork")
        startForegroundCompat(R.string.notification_waiting_network)
    }

    private fun enterAlwaysOnWaitingForAuthentication() {
        alwaysOnWaiting = true
        setStatus("waitingForAuthentication")
        startForegroundCompat(R.string.notification_waiting_authentication)
    }

    private fun handleTileSessionRestoreResult(result: AlwaysOnSessionRestoreResult) {
        if (result.code.isNotBlank()) {
            RedactedDiagnostics.recordVpnServiceState(
                applicationContext,
                state = "sessionRestore",
                code = result.code,
            )
        }
        val shouldHandle = synchronized(alwaysOnRestoreLock) {
            if (!tileRestoreInFlight) {
                false
            } else {
                tileRestoreInFlight = false
                true
            }
        }
        if (!shouldHandle) return

        when (result.outcome) {
            AlwaysOnSessionRestoreOutcome.Authenticated -> {
                startPreparedInternal(RealVpnStartMode.TILE)
            }
            AlwaysOnSessionRestoreOutcome.WaitingForUserAuthentication,
            AlwaysOnSessionRestoreOutcome.InvalidSession,
            -> {
                synchronized(alwaysOnRestoreLock) {
                    tileWaitingForAuthentication = true
                }
                releaseServiceEntry(ConnectionEntryOwner.TILE_SERVICE)
                setStatus("waitingForAuthentication")
                startForegroundCompat(R.string.notification_waiting_authentication)
            }
            AlwaysOnSessionRestoreOutcome.TransientFailure -> {
                releaseServiceEntry(ConnectionEntryOwner.TILE_SERVICE)
                publishFailure(
                    "tileSessionRestore",
                    RealVpnFailure(
                        code = result.code.ifBlank { "sessionRestoreUnavailable" },
                        message = result.message.ifBlank {
                            "Unable to restore the saved authentication session"
                        },
                    ),
                )
                stopInternal()
            }
        }
    }

    private fun getAlwaysOnSessionRestorer(): AlwaysOnSessionRestorer =
        synchronized(alwaysOnRestoreLock) {
            alwaysOnSessionRestorer ?: AlwaysOnSessionRestorer(
                sessionStore = AuthSessionStore(applicationContext),
                deviceIdentityProvider = DeviceIdentityProvider(applicationContext),
                bridge = goCoreBridge,
            ).also { alwaysOnSessionRestorer = it }
        }

    private fun cancelAlwaysOnRestore(forceBridgeCancellation: Boolean) {
        var hasAlwaysOnWork = false
        val cancelBridge = synchronized(alwaysOnRestoreLock) {
            hasAlwaysOnWork =
                alwaysOnWaiting || alwaysOnRestoreInFlight || alwaysOnRestoreFuture != null
            if (!hasAlwaysOnWork) {
                false
            } else {
                val shouldCancel = forceBridgeCancellation || alwaysOnRestoreInFlight
                alwaysOnRestoreFuture?.cancel(false)
                alwaysOnRestoreFuture = null
                alwaysOnRestoreInFlight = false
                alwaysOnRestoreRevision = Long.MIN_VALUE
                alwaysOnRetryPolicy.resetForRevision(Long.MIN_VALUE)
                shouldCancel
            }
        }
        if (!hasAlwaysOnWork) return
        alwaysOnSessionRestorer?.let { restorer ->
            if (cancelBridge) restorer.cancel() else restorer.invalidate()
        }
    }

    private fun handoffToForegroundAuthentication() {
        if (alwaysOnWaiting) {
            cancelAlwaysOnRestore(forceBridgeCancellation = true)
            alwaysOnWaiting = false
        }
        if (hasTileRestoreOrAuthenticationWaiting()) {
            cancelTileRestore(forceBridgeCancellation = true)
        }
    }

    private fun hasTileRestoreOrAuthenticationWaiting(): Boolean = synchronized(alwaysOnRestoreLock) {
        tileRestoreInFlight || tileWaitingForAuthentication
    }

    private fun cancelTileRestore(forceBridgeCancellation: Boolean) {
        var hadTileWork = false
        val cancelBridge = synchronized(alwaysOnRestoreLock) {
            hadTileWork = tileRestoreInFlight || tileWaitingForAuthentication
            if (!hadTileWork) {
                false
            } else {
                val shouldCancel = forceBridgeCancellation || tileRestoreInFlight
                tileRestoreInFlight = false
                tileWaitingForAuthentication = false
                shouldCancel
            }
        }
        if (!hadTileWork) return
        alwaysOnSessionRestorer?.let { restorer ->
            if (cancelBridge) restorer.cancel() else restorer.invalidate()
        }
        releaseServiceEntry(ConnectionEntryOwner.TILE_SERVICE)
    }

    private fun systemAlwaysOnEnabled(): Boolean = runCatching { isAlwaysOn }
        .getOrDefault(false)

    private fun startPreparedInternal(mode: RealVpnStartMode) {
        activeStartMode = mode
        val generation = synchronized(stateLock) {
            if (lifecycle.beginSession()) bridgeSessionTracker.beginSession() else null
        }
        if (generation == null) {
            Log.i(REAL_VPN_LOG_TAG, "ignored duplicate start request")
            return
        }
        when (mode) {
            RealVpnStartMode.MANUAL -> claimServiceEntry(ConnectionEntryOwner.ACTIVITY)
            RealVpnStartMode.TILE -> claimServiceEntry(ConnectionEntryOwner.TILE_SERVICE)
            RealVpnStartMode.ALWAYS_ON -> Unit
        }

        val canStart = synchronized(recoveryLock) {
            l3RecoveryActive = false
            val sessionStart = underlaySessionStart()
            recoveryCoordinator.beginSession(
                snapshot = sessionStart.snapshot,
                activeUnderlay = sessionStart.activeUnderlay,
            )
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
            setStatus("preparing")
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
                if (recovering) {
                    R.string.notification_recovering
                } else {
                    R.string.notification_connecting
                },
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
                setStatus("attaching")
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
            goCoreBridge.startRealVpn(tunFd.toLong(), socketProtector, goListener(generation))
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
                publishFailure("startPreparedInternal", retainedFailure)
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
            setStatus("stopping")
        }
        goCoreBridge.stopRealVpn()
        stopForegroundCompat()
        when (val outcome = synchronized(stateLock) { lifecycle.terminalOutcome() }) {
            is RealVpnTerminalOutcome.Error -> {
                publishFailure("stopInternal", outcome.failure)
            }
            RealVpnTerminalOutcome.Stopped -> setStatus("stopped")
            null -> Unit
        }
        releaseServiceEntry()
        QuickSettingsTileService.requestRefresh(applicationContext)
        stopSelf()
    }

    private fun onUnderlayNetworkChanged(snapshot: UnderlayNetworkSnapshot) {
        val commands = synchronized(recoveryLock) {
            recoveryCoordinator.onNetworkChanged(snapshot)
        }
        handleRecoveryCommands(commands)
        if (alwaysOnWaiting && RealVpnStateStore.state.value.state != "waitingForAuthentication") {
            if (!executor.isShutdown) {
                executor.execute {
                    scheduleAlwaysOnRestoreForNetwork(snapshot, resetRevision = false)
                }
            }
        }
    }

    private fun handleRecoveryCommands(commands: List<RealVpnRecoveryCommand>) {
        publishRecoveryPresentation()
        commands.forEach { command ->
            when (command) {
                RealVpnRecoveryCommand.CancelDebounce -> cancelRecoveryDebounce()
                is RealVpnRecoveryCommand.ScheduleDebounce -> scheduleRecoveryDebounce(command.generation)
                RealVpnRecoveryCommand.StopSession -> requestRecoveryStop()
                RealVpnRecoveryCommand.StartSession -> requestRecoveryStart()
            }
        }
    }

    private fun scheduleRecoveryDebounce(generation: Long) {
        if (watchdogExecutor.isShutdown) return
        synchronized(recoveryLock) {
            recoveryDebounce?.cancel(false)
            recoveryDebounce = watchdogExecutor.schedule(
                {
                    val commands = synchronized(recoveryLock) {
                        recoveryDebounce = null
                        recoveryCoordinator.onDebounceElapsed(generation)
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
            executor.execute { startPreparedInternal(activeStartMode) }
        }
    }

    private fun terminateRecovery() {
        val commands = synchronized(recoveryLock) {
            l3RecoveryActive = false
            recoveryCoordinator.terminate()
        }
        handleRecoveryCommands(commands)
    }

    private fun isStoppingForRecovery(): Boolean = synchronized(recoveryLock) {
        recoveryCoordinator.isStoppingForRecovery
    }

    private fun isUnderlayRecoveryInProgress(): Boolean = synchronized(recoveryLock) {
        recoveryCoordinator.presentation != RealVpnRecoveryPresentation.NONE
    }

    private fun underlaySnapshot(): UnderlayNetworkSnapshot =
        if (::underlayNetworkMonitor.isInitialized) {
            underlayNetworkMonitor.snapshot()
        } else {
            UnderlayNetworkSnapshot()
        }

    private fun underlaySessionStart(): UnderlaySessionStart =
        if (::underlayNetworkMonitor.isInitialized) {
            underlayNetworkMonitor.captureSessionStart()
        } else {
            UnderlaySessionStart(
                snapshot = UnderlayNetworkSnapshot(),
                activeUnderlay = null,
            )
        }

    private fun publishRecoveryPresentation() {
        val presentation = synchronized(recoveryLock) {
            combinedRealVpnRecoveryPresentation(
                underlay = recoveryCoordinator.presentation,
                l3Recovering = l3RecoveryActive,
            )
        }
        if (presentation == publishedRecoveryPresentation) return
        val previous = publishedRecoveryPresentation
        publishedRecoveryPresentation = presentation
        when (presentation) {
            RealVpnRecoveryPresentation.NONE -> {
                if (previous != RealVpnRecoveryPresentation.NONE && acceptsStartProgress()) {
                    setStatus("active")
                    startForegroundCompat(R.string.notification_connected)
                }
            }
            RealVpnRecoveryPresentation.RECOVERING -> {
                setStatus("recovering")
                startForegroundCompat(R.string.notification_recovering)
            }
            RealVpnRecoveryPresentation.WAITING_FOR_NETWORK -> {
                setStatus("waitingForNetwork")
                startForegroundCompat(R.string.notification_waiting_network)
            }
        }
    }

    private fun acceptsStartProgress(): Boolean = synchronized(stateLock) { lifecycle.acceptsProgress() }

    private fun claimServiceEntry(owner: ConnectionEntryOwner) {
        synchronized(stateLock) {
            serviceEntryOwner = owner
        }
    }

    private fun releaseServiceEntry(expectedOwner: ConnectionEntryOwner? = null) {
        val owner = synchronized(stateLock) {
            serviceEntryOwner?.takeIf { expectedOwner == null || it == expectedOwner }
                ?.also { serviceEntryOwner = null }
        }
        owner?.let(ConnectionEntryArbiter::finish)
    }

    /** A cancelled foreground attempt can dispatch STOP before its service start arrives. */
    private fun releaseUnclaimedActivityEntry() {
        val hasServiceEntry = synchronized(stateLock) { serviceEntryOwner != null }
        if (!hasServiceEntry) {
            ConnectionEntryArbiter.finish(ConnectionEntryOwner.ACTIVITY)
        }
    }

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

    private fun setStatus(state: String) {
        Log.i(REAL_VPN_LOG_TAG, "service state=$state")
        RedactedDiagnostics.recordVpnServiceState(applicationContext, state)
        RealVpnStateStore.setStatus(state)
        QuickSettingsTileService.requestRefresh(applicationContext)
    }

    private fun publishFailure(origin: String, failure: RealVpnFailure) {
        Log.e(REAL_VPN_LOG_TAG, "terminal failure origin=$origin code=${failure.code}")
        RedactedDiagnostics.recordVpnServiceState(applicationContext, "error", failure.code)
        RealVpnStateStore.setError(failure.code)
        QuickSettingsTileService.requestRefresh(applicationContext)
    }

    private fun publishAlwaysOnDisconnectBlocked() {
        setStatus("alwaysOnDisconnectBlocked")
        val notification = Notification.Builder(this, VPN_STATUS_CHANNEL)
            .setContentTitle(getString(R.string.notification_always_on_title))
            .setContentText(getString(R.string.notification_always_on_text))
            .setSmallIcon(R.drawable.ic_stat_cedar)
            .setContentIntent(openVpnSettingsPendingIntent())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_SYSTEM)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(ALWAYS_ON_GUIDANCE_NOTIFICATION_ID, notification)
        }.onFailure { error ->
            Log.w(REAL_VPN_LOG_TAG, "Unable to publish Always-on guidance", error)
        }
    }

    private class RealVpnStartFailure(
        val code: String,
        val stage: String,
        message: String,
    ) : IllegalStateException(message)

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            VPN_STATUS_CHANNEL,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(channel)
            deleteNotificationChannel(LEGACY_REAL_VPN_CHANNEL)
        }
    }

    private fun startForegroundCompat(@StringRes textRes: Int) {
        currentNotificationTextRes = textRes
        val notification = buildVpnNotification(textRes)

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

    private fun buildVpnNotification(@StringRes textRes: Int): Notification {
        val builder = Notification.Builder(this, VPN_STATUS_CHANNEL)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(textRes))
            .setSmallIcon(R.drawable.ic_stat_cedar)
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_SERVICE)
        return builder.build()
    }

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openVpnSettingsPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        ALWAYS_ON_GUIDANCE_NOTIFICATION_ID,
        Intent(Settings.ACTION_VPN_SETTINGS),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun refreshCurrentNotification() {
        if (synchronized(stateLock) { lifecycle.acceptsProgress() }) {
            startForegroundCompat(currentNotificationTextRes)
        }
    }

    private fun stopForegroundCompat() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .cancel(ALWAYS_ON_GUIDANCE_NOTIFICATION_ID)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
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
)

object RealVpnStateStore {
    private val mutableState = MutableStateFlow(RealVpnUiState())
    val state: StateFlow<RealVpnUiState> = mutableState.asStateFlow()

    fun update(event: GoVpnEvent) {
        mutableState.update {
            it.copy(
                state = event.state,
                code = event.code,
            )
        }
    }

    fun setStatus(nextState: String) {
        mutableState.update { it.copy(state = nextState, code = "") }
    }

    fun setError(code: String) {
        mutableState.update { it.copy(state = "error", code = code) }
    }

    fun reset() {
        mutableState.value = RealVpnUiState()
    }
}
