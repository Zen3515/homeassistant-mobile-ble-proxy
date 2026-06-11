package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyLaunchContractTest {
    @Test
    fun `tile launch action requests proxy start`() {
        assertTrue(
            ProxyLaunchContract.shouldStartProxy(
                ProxyLaunchContract.ACTION_OPEN_AND_START_PROXY,
            ),
        )
    }

    @Test
    fun `ordinary app launches do not request proxy start`() {
        assertFalse(ProxyLaunchContract.shouldStartProxy(null))
        assertFalse(ProxyLaunchContract.shouldStartProxy("android.intent.action.MAIN"))
    }
}
