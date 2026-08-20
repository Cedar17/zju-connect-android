package io.github.cedar17.zjuconnect

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.ContextCompat

private const val QUICK_SETTINGS_TILE_LOG_TAG = "ZjuConnectTile"

/**
 * Direct VPN controls for a user-added Quick Settings tile. The service never
 * reads saved passwords or completes an interactive authentication challenge.
 */
class QuickSettingsTileService : TileService() {
    companion object {
        /** Active tiles can ask System UI to deliver a fresh listening callback. */
        fun requestRefresh(context: Context) {
            runCatching {
                TileService.requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, QuickSettingsTileService::class.java),
                )
            }.onFailure { error ->
                Log.w(QUICK_SETTINGS_TILE_LOG_TAG, "Unable to refresh Quick Settings tile", error)
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        renderTile()
    }

    override fun onClick() {
        if (currentPresentation().action == QuickSettingsTileAction.NONE) {
            renderTile()
        } else if (isLocked && isSecure) {
            unlockAndRun { dispatchTileAction() }
        } else {
            dispatchTileAction()
        }
    }

    private fun currentPresentation(): QuickSettingsTilePresentation = quickSettingsTilePresentation(
        vpnState = RealVpnStateStore.state.value,
        entryInProgress = ConnectionEntryArbiter.isInProgress(),
    )

    private fun dispatchTileAction() {
        when (currentPresentation().action) {
            QuickSettingsTileAction.CONNECT -> beginTileConnection()
            QuickSettingsTileAction.DISCONNECT -> requestDisconnect()
            QuickSettingsTileAction.OPEN_APP -> openAppForConnection()
            QuickSettingsTileAction.NONE -> renderTile()
        }
    }

    private fun beginTileConnection() {
        if (!ConnectionEntryArbiter.tryBegin(ConnectionEntryOwner.TILE_SERVICE)) {
            renderTile()
            requestRefresh(this)
            return
        }
        val permissionIntent = runCatching { VpnService.prepare(this) }
            .getOrElse { error ->
                Log.e(QUICK_SETTINGS_TILE_LOG_TAG, "Unable to check VPN permission", error)
                RedactedDiagnostics.recordVpnServiceState(
                    applicationContext,
                    state = "error",
                    code = "vpnStartDispatchFailed",
                )
                RealVpnStateStore.setError("vpnStartDispatchFailed")
                ConnectionEntryArbiter.finish(ConnectionEntryOwner.TILE_SERVICE)
                renderTile()
                requestRefresh(this)
                return
            }
        if (permissionIntent != null) {
            ConnectionEntryArbiter.finish(ConnectionEntryOwner.TILE_SERVICE)
            renderTile()
            requestRefresh(this)
            openAppForConnection()
            return
        }

        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, RealVpnService::class.java)
                    .setAction(RealVpnService.ACTION_START)
                    .putExtra(REAL_VPN_EXTRA_START_SOURCE, REAL_VPN_START_SOURCE_TILE),
            )
        }.onFailure { error ->
            Log.e(QUICK_SETTINGS_TILE_LOG_TAG, "Unable to start VPN service", error)
            RedactedDiagnostics.recordVpnServiceState(
                applicationContext,
                state = "error",
                code = "vpnStartDispatchFailed",
            )
            RealVpnStateStore.setError("vpnStartDispatchFailed")
            ConnectionEntryArbiter.finish(ConnectionEntryOwner.TILE_SERVICE)
        }
        renderTile()
        requestRefresh(this)
    }

    private fun requestDisconnect() {
        runCatching {
            startService(
                Intent(this, RealVpnService::class.java)
                    .setAction(RealVpnService.ACTION_STOP),
            )
        }.onFailure { error ->
            Log.e(QUICK_SETTINGS_TILE_LOG_TAG, "Unable to stop VPN service", error)
        }
        renderTile()
        requestRefresh(this)
    }

    private fun openAppForConnection() {
        val intent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }.onFailure { error ->
            Log.e(QUICK_SETTINGS_TILE_LOG_TAG, "Unable to open app from Quick Settings", error)
        }
    }

    private fun renderTile() {
        val presentation = currentPresentation()
        val status = getString(quickSettingsTileStatusRes(presentation.status))
        qsTile?.apply {
            label = getString(R.string.quick_settings_tile_label)
            subtitle = null
            contentDescription = getString(
                R.string.quick_settings_tile_content_description,
                getString(R.string.quick_settings_tile_label),
                status,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                stateDescription = status
            }
            icon = Icon.createWithResource(this@QuickSettingsTileService, R.drawable.ic_qs_cedar)
            state = when (presentation.visualState) {
                QuickSettingsTileVisualState.ACTIVE -> Tile.STATE_ACTIVE
                QuickSettingsTileVisualState.INACTIVE -> Tile.STATE_INACTIVE
            }
            updateTile()
        }
    }
}

internal fun quickSettingsTileStatusRes(status: QuickSettingsTileStatus): Int = when (status) {
    QuickSettingsTileStatus.DISCONNECTED -> R.string.quick_settings_tile_status_disconnected
    QuickSettingsTileStatus.CONNECTING -> R.string.quick_settings_tile_status_connecting
    QuickSettingsTileStatus.CONNECTED -> R.string.quick_settings_tile_status_connected
    QuickSettingsTileStatus.RECOVERING -> R.string.quick_settings_tile_status_recovering
    QuickSettingsTileStatus.OPEN_APP_FOR_LOGIN -> R.string.quick_settings_tile_status_open_app
    QuickSettingsTileStatus.ALWAYS_ON_MANAGED -> R.string.quick_settings_tile_status_always_on
}
