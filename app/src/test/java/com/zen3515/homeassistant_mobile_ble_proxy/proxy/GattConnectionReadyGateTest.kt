package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GattConnectionReadyGateTest {
    @Test
    fun cachedConnectionIsReadyWhenLinkConnects() {
        val gate = GattConnectionReadyGate(requiresFreshServices = false)

        assertTrue(gate.onLinkConnected())
        assertTrue(gate.isReady)
        assertFalse(gate.isWaitingForFreshServices)
        assertFalse(gate.onFreshServicesDiscovered())
        assertFalse(gate.onLinkConnected())
    }

    @Test
    fun noCacheConnectionWaitsForFreshServices() {
        val gate = GattConnectionReadyGate(requiresFreshServices = true)

        assertFalse(gate.onLinkConnected())
        assertFalse(gate.isReady)
        assertTrue(gate.isWaitingForFreshServices)

        assertTrue(gate.onFreshServicesDiscovered())
        assertTrue(gate.isReady)
        assertFalse(gate.isWaitingForFreshServices)
        assertFalse(gate.onFreshServicesDiscovered())
    }
}
