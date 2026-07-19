package com.zen3515.homeassistant_mobile_ble_proxy

import android.app.Application
import com.zen3515.homeassistant_mobile_ble_proxy.proxy.CrashDiagnostics

class BleProxyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashDiagnostics.initialize(this)
    }
}
