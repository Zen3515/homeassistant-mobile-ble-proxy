package com.zen3515.homeassistant_mobile_ble_proxy.proxy

object ProxyLaunchContract {
    const val ACTION_OPEN_AND_START_PROXY =
        "com.zen3515.homeassistant_mobile_ble_proxy.action.OPEN_AND_START_PROXY"

    fun shouldStartProxy(action: String?): Boolean {
        return action == ACTION_OPEN_AND_START_PROXY
    }
}
