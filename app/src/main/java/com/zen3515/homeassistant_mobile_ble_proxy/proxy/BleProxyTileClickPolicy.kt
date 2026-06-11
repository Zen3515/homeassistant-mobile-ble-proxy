package com.zen3515.homeassistant_mobile_ble_proxy.proxy

enum class BleProxyTileClickAction {
    OPEN_APP_AND_START_PROXY,
    STOP_PROXY,
}

object BleProxyTileClickPolicy {
    fun actionForServiceRunning(isRunning: Boolean): BleProxyTileClickAction {
        return if (isRunning) {
            BleProxyTileClickAction.STOP_PROXY
        } else {
            BleProxyTileClickAction.OPEN_APP_AND_START_PROXY
        }
    }
}
