package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyRuntimeStateTest {
    @After
    fun resetLogcatMirroring() {
        ProxyRuntimeState.setLogcatMirroringEnabled(false)
    }

    @Test
    fun `logcat mirroring is disabled until explicitly enabled`() {
        ProxyRuntimeState.setLogcatMirroringEnabled(false)
        assertFalse(ProxyRuntimeState.isLogcatMirroringEnabled())

        ProxyRuntimeState.setLogcatMirroringEnabled(true)
        assertTrue(ProxyRuntimeState.isLogcatMirroringEnabled())
    }
}
