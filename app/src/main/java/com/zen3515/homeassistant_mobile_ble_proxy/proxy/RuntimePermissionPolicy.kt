package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import android.Manifest
import android.os.Build

/** Builds the runtime permission request without expanding plain-proxy permissions. */
object RuntimePermissionPolicy {
    fun requiredPermissions(
        sdkInt: Int,
        bleAdvProxyEnabled: Boolean,
    ): List<String> = buildList {
        if (sdkInt >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (bleAdvProxyEnabled) {
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (sdkInt in Build.VERSION_CODES.Q..Build.VERSION_CODES.R) {
            add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.distinct()
}
