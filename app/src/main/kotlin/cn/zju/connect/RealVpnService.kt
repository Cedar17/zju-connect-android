package cn.zju.connect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.zju.connect.gocore.core.SocketProtector
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val REAL_VPN_CHANNEL = "zju_connect_real_vpn"
private const val REAL_VPN_NOTIFICATION_ID = 1002

/** Owns the production Android TUN and the authenticated zju-connect client. */
class RealVpnService : VpnService() {
    companion object {
        const val ACTION_START = "cn.zju.connect.action.START_REAL_VPN"
        const val ACTION_STOP = "cn.zju.connect.action.STOP_REAL_VPN"
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val goCoreBridge = GoCoreBridge()
    private val stateLock = Any()

    @Volatile
    private var sessionStarted = false

    @Volatile
    private var stopRequested = false

    /** Keep the first data-plane failure visible after the cleanup callback. */
    private var terminalFailure: Pair<String, String>? = null

    private val socketProtector = object : SocketProtector {
        override fun protect(socketFd: Long): Boolean = this@RealVpnService.protect(socketFd.toInt())
    }

    private val goListener: (GoVpnEvent) -> Unit = { event ->
        RealVpnStateStore.update(event)
        if (event.state == "error") {
            synchronized(stateLock) {
                terminalFailure = event.code.ifBlank { "vpnDataPlaneFailed" } to event.message
            }
            requestStop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> requestStart()
            ACTION_STOP -> requestStop()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        requestStop()
        super.onRevoke()
    }

    override fun onDestroy() {
        val destroyedUnexpectedly = synchronized(stateLock) {
            sessionStarted && !stopRequested
        }
        if (destroyedUnexpectedly) {
            synchronized(stateLock) {
                terminalFailure = "serviceDestroyed" to
                    "Android destroyed the VPN service before it completed"
            }
        }
        stopInternal()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun requestStart() {
        executor.execute { startInternal() }
    }

    private fun requestStop() {
        synchronized(stateLock) {
            stopRequested = true
        }
        executor.execute { stopInternal() }
    }

    private fun startInternal() {
        synchronized(stateLock) {
            if (sessionStarted) {
                return
            }
            stopRequested = false
            terminalFailure = null
            sessionStarted = true
        }

        RealVpnStateStore.setStatus("preparing", "Preparing the authenticated aTrust VPN")
        var detachedTunFd: Int? = null
        try {
            // Android requires a foreground service to publish its notification
            // before doing network work. PrepareRealVpn performs TLS requests
            // and resource parsing, so doing this afterwards can make Android
            // kill the service before the TUN is established.
            startForegroundCompat()
            val config = goCoreBridge.prepareRealVpn()
            if (config.state == "error" || config.address.isBlank() || config.routes.isEmpty()) {
                error(config.message.ifBlank { "The aTrust VPN configuration is unavailable" })
            }

            val builder = Builder()
                .setSession("ZJU Connect")
                .setMtu(config.mtu.coerceIn(576, 1500))
                .setBlocking(true)
                .addAddress(config.address, 32)

            config.routes.forEach { route ->
                builder.addRoute(route.address, route.prefixLength)
            }

            val tun = builder.establish()
                ?: error("Android refused to establish the VPN interface")
            val tunFd = tun.detachFd()
            detachedTunFd = tunFd
            tun.close()

            RealVpnStateStore.setStatus("attaching", "Attaching the Android VPN interface")
            goCoreBridge.startRealVpn(tunFd.toLong(), socketProtector, goListener)
            detachedTunFd = null
        } catch (error: Throwable) {
            detachedTunFd?.let { fd ->
                runCatching { android.os.ParcelFileDescriptor.adoptFd(fd).close() }
            }
            goCoreBridge.stopRealVpn()
            RealVpnStateStore.setError(
                code = "vpnStartFailed",
                message = error.message ?: "Unable to start the real aTrust VPN",
            )
            synchronized(stateLock) {
                terminalFailure = null
                sessionStarted = false
            }
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun stopInternal() {
        val shouldStop = synchronized(stateLock) {
            if (!sessionStarted) {
                false
            } else {
                sessionStarted = false
                true
            }
        }
        val failureBeforeCleanup = synchronized(stateLock) { terminalFailure }

        if (shouldStop) {
            RealVpnStateStore.setStatus("stopping", "Stopping the real aTrust VPN")
        }
        goCoreBridge.stopRealVpn()
        stopForegroundCompat()
        val failure = synchronized(stateLock) {
            terminalFailure ?: failureBeforeCleanup
        }
        synchronized(stateLock) {
            terminalFailure = null
        }
        if (failure != null) {
            RealVpnStateStore.setError(failure.first, failure.second)
        } else if (!shouldStop) {
            RealVpnStateStore.setStatus("stopped", "Real aTrust VPN is stopped")
        }
        stopSelf()
    }

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
