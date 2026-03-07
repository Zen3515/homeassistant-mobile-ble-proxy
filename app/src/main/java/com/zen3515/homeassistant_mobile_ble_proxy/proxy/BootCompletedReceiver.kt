package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val settingsRepository = SettingsRepository(context.applicationContext)
        val shouldStart = runBlocking {
            settingsRepository.settings.firstOrNull()?.autoStartOnBoot ?: false
        }

        if (shouldStart) {
            ProxyServiceController.start(context.applicationContext)
        }
    }
}
