package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
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
        val currentlyRunning = ProxyQuickSettingsTileStateStore.isServiceRunning(context)
        if (currentlyRunning) {
            ProxyServiceController.stop(context)
            updateTileState(
                isRunning = false,
                secondaryLabel = getString(R.string.qs_tile_status_stopping),
            )
        } else {
            ProxyServiceController.start(context)
            updateTileState(
                isRunning = true,
                secondaryLabel = getString(R.string.qs_tile_status_starting),
            )
        }
    }

    private fun refreshTile() {
        val running = ProxyQuickSettingsTileStateStore.isServiceRunning(applicationContext)
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

    companion object {
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
