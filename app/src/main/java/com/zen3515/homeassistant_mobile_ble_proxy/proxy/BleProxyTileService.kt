package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.zen3515.homeassistant_mobile_ble_proxy.MainActivity
import com.zen3515.homeassistant_mobile_ble_proxy.R

class BleProxyTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        refreshTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()

        val context = applicationContext
        val currentlyRunning = resolveServiceRunning(context)
        val locked = isLocked
        when (BleProxyTileClickPolicy.actionForServiceRunning(currentlyRunning)) {
            BleProxyTileClickAction.STOP_PROXY -> {
                ProxyRuntimeState.appendLog("Tile stop requested while ${if (locked) "locked" else "unlocked"}")
                ProxyServiceController.stop(context)
                updateTileState(
                    isRunning = true,
                    secondaryLabel = getString(R.string.qs_tile_status_stopping),
                )
            }

            BleProxyTileClickAction.OPEN_APP_AND_START_PROXY -> {
                ProxyRuntimeState.appendLog("Tile app-open start requested while ${if (locked) "locked" else "unlocked"}")
                openAppAndRequestStart()
                updateTileState(
                    isRunning = false,
                    secondaryLabel = getString(R.string.qs_tile_status_opening),
                )
            }
        }
    }

    private fun refreshTile() {
        val running = resolveServiceRunning(applicationContext)
        updateTileState(
            isRunning = running,
            secondaryLabel = getString(
                if (running) {
                    R.string.qs_tile_status_running
                } else {
                    R.string.qs_tile_status_stopped
                },
            ),
        )
    }

    private fun updateTileState(isRunning: Boolean, secondaryLabel: String) {
        val tile = qsTile ?: return
        val label = getString(R.string.qs_tile_label)
        tile.label = label
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = secondaryLabel
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = secondaryLabel
        }
        tile.contentDescription = "$label, $secondaryLabel"
        tile.updateTile()
    }

    private fun resolveServiceRunning(context: Context): Boolean {
        return ProxyServiceController.reconcileObservedRunning(context)
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openAppAndRequestStart() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ProxyLaunchContract.ACTION_OPEN_AND_START_PROXY
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                TILE_START_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        private const val TILE_START_REQUEST_CODE = 10_053

        fun onServiceRunningChanged(context: Context, running: Boolean) {
            ProxyQuickSettingsTileStateStore.setServiceRunning(context, running)
            requestRefresh(context)
        }

        private fun requestRefresh(context: Context) {
            val component = ComponentName(context, BleProxyTileService::class.java)
            runCatching {
                requestListeningState(context, component)
            }
        }
    }
}
