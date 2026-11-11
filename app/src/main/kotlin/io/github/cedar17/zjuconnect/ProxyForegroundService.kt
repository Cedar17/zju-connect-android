package io.github.cedar17.zjuconnect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val PROXY_STATUS_CHANNEL = "zju_connect_proxy_status"
private const val PROXY_NOTIFICATION_ID = 1004

data class ProxyUiState(
    val state: String = "idle",
    val code: String = "",
    val socksPort: Int = 0,
    val httpPort: Int = 0,
)

object ProxyStateStore {
    private val mutableState = MutableStateFlow(ProxyUiState())
    val state: StateFlow<ProxyUiState> = mutableState.asStateFlow()

    fun update(event: GoProxyEvent) {
        mutableState.update {
            it.copy(
                state = event.state,
                code = event.code,
                socksPort = event.socksPort,
                httpPort = event.httpPort,
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
        mutableState.value = ProxyUiState()
    }
}

/**
 * Foreground host for the experimental proxy-only mode: a plain service, not
 * a VpnService, so the local SOCKS5/HTTP proxies coexist with another VPN
 * app. Normal users never enable this mode.
 */
class ProxyForegroundService : Service() {
    private val goCoreBridge = GoCoreBridge()
    private var connectionEntryOwner: ConnectionEntryOwner? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopProxyInternal()
                return START_NOT_STICKY
            }
            else -> startProxyInternal()
        }
        return START_STICKY
    }

    private fun startProxyInternal() {
        createNotificationChannel()
        startForegroundCompat(R.string.notification_proxy_connecting)

        val settings = ProxySettingsStore(applicationContext).read()
        if (!settings.isValid()) {
            ProxyStateStore.setError("proxySettingsInvalid")
            stopSelf()
            return
        }

        val bridge = goCoreBridge
        val prepared = bridge.prepareProxy()
        if (prepared.state != "prepared") {
            ProxyStateStore.setError(prepared.code.ifBlank { "vpnSetupFailed" })
            stopSelf()
            return
        }
        ProxyStateStore.setStatus("preparing")

        val finalEvent = bridge.startProxy(settings.socksPort, settings.httpPort) { event ->
            handleProxyEvent(event)
        }
        handleProxyEvent(finalEvent)
    }

    private fun handleProxyEvent(event: GoProxyEvent) {
        ProxyStateStore.update(event)
        when (event.state) {
            "active" -> {
                claimServiceEntry()
                startForegroundCompat(R.string.notification_proxy_connected)
            }
            "error" -> {
                releaseServiceEntry()
                stopSelf()
            }
        }
    }

    private fun stopProxyInternal() {
        goCoreBridge.stopProxy { event ->
            ProxyStateStore.update(event)
        }
        releaseServiceEntry()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun claimServiceEntry() {
        if (connectionEntryOwner != null) return
        if (ConnectionEntryArbiter.tryBegin(ConnectionEntryOwner.PROXY_SERVICE)) {
            connectionEntryOwner = ConnectionEntryOwner.PROXY_SERVICE
            QuickSettingsTileService.requestRefresh(applicationContext)
        }
    }

    private fun releaseServiceEntry() {
        if (connectionEntryOwner == null) return
        ConnectionEntryArbiter.finish(ConnectionEntryOwner.PROXY_SERVICE)
        connectionEntryOwner = null
        QuickSettingsTileService.requestRefresh(applicationContext)
    }

    override fun onDestroy() {
        releaseServiceEntry()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            PROXY_STATUS_CHANNEL,
            getString(R.string.notification_proxy_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.notification_proxy_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundCompat(@StringRes textRes: Int) {
        val notification = buildProxyNotification(textRes)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                PROXY_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(PROXY_NOTIFICATION_ID, notification)
        }
    }

    private fun buildProxyNotification(@StringRes textRes: Int): Notification =
        Notification.Builder(this, PROXY_STATUS_CHANNEL)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(textRes))
            .setSmallIcon(R.drawable.ic_stat_cedar)
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_START = "io.github.cedar17.zjuconnect.action.START_PROXY"
        const val ACTION_STOP = "io.github.cedar17.zjuconnect.action.STOP_PROXY"
    }
}
