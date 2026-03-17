package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import android.content.Context

object ProxyQuickSettingsTileStateStore {
    private const val PREFS_NAME = "proxy_quick_settings_tile_state"
    private const val KEY_SERVICE_RUNNING = "service_running"

    fun isServiceRunning(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SERVICE_RUNNING, false)
    }

    fun setServiceRunning(context: Context, running: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SERVICE_RUNNING, running)
            .apply()
    }
}
