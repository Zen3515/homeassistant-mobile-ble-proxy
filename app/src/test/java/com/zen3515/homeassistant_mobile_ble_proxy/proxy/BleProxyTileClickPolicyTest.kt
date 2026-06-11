package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Test

class BleProxyTileClickPolicyTest {
    @Test
    fun `inactive tile click opens app and requests proxy start`() {
        assertEquals(
            BleProxyTileClickAction.OPEN_APP_AND_START_PROXY,
            BleProxyTileClickPolicy.actionForServiceRunning(isRunning = false),
        )
    }

    @Test
    fun `active tile click stops proxy directly`() {
        assertEquals(
            BleProxyTileClickAction.STOP_PROXY,
            BleProxyTileClickPolicy.actionForServiceRunning(isRunning = true),
        )
    }
}
