package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object ProxyServiceController {
    const val ACTION_START = "com.zen3515.homeassistant_mobile_ble_proxy.action.START_PROXY"
    const val ACTION_STOP = "com.zen3515.homeassistant_mobile_ble_proxy.action.STOP_PROXY"

    fun start(context: Context) {
        val intent = Intent(context, BleProxyForegroundService::class.java).apply {
            action = ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, BleProxyForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        ContextCompat.startForegroundService(context, intent)
    }
}
