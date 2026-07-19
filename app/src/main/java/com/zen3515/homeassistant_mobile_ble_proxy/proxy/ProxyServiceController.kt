package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import android.app.NotificationManager
import android.os.Build
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object ProxyServiceController {
    const val ACTION_START = "com.zen3515.homeassistant_mobile_ble_proxy.action.START_PROXY"
    const val ACTION_STOP = "com.zen3515.homeassistant_mobile_ble_proxy.action.STOP_PROXY"

    fun start(context: Context) {
        CrashDiagnostics.recordLifecycle("controller request=start")
        val intent = Intent(context, BleProxyForegroundService::class.java).apply {
            action = ACTION_START
        }
        runCatching {
            ContextCompat.startForegroundService(context, intent)
        }.onFailure { error ->
            val detail = error.message ?: error.javaClass.simpleName
            CrashDiagnostics.recordLifecycle(
                "controller start-failed type=${error.javaClass.simpleName}",
            )
            ProxyRuntimeState.setError("Unable to request proxy start: $detail")
            ProxyRuntimeState.appendLog("Unable to request proxy start: $detail")
        }
    }

    fun stop(context: Context) {
        CrashDiagnostics.recordLifecycle("controller request=stop")
        val intent = Intent(context, BleProxyForegroundService::class.java)
        runCatching {
            context.stopService(intent)
        }.onFailure { error ->
            val detail = error.message ?: error.javaClass.simpleName
            CrashDiagnostics.recordLifecycle(
                "controller stop-failed type=${error.javaClass.simpleName}",
            )
            ProxyRuntimeState.setError("Unable to request proxy stop: $detail")
            ProxyRuntimeState.appendLog("Unable to request proxy stop: $detail")
        }
    }

    fun isActuallyRunning(context: Context): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return@runCatching ProxyQuickSettingsTileStateStore.isServiceRunning(context)
            }
            val manager = context.getSystemService(NotificationManager::class.java) ?: return@runCatching false
            manager.activeNotifications.any { notification ->
                notification.id == BleProxyForegroundService.NOTIFICATION_ID
            }
        }.getOrElse {
            ProxyQuickSettingsTileStateStore.isServiceRunning(context)
        }
    }

    fun reconcileObservedRunning(context: Context): Boolean {
        val running = isActuallyRunning(context)
        if (ProxyQuickSettingsTileStateStore.isServiceRunning(context) != running) {
            ProxyQuickSettingsTileStateStore.setServiceRunning(context, running)
        }
        ProxyRuntimeState.reconcileObservedServiceRunning(running)
        return running
    }
}
