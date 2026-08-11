package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleAdvFeatureGateTest {
    @Test
    fun `disabled mode exposes no entities or services and constructs no radio runtime`() {
        var runtimeCreations = 0
        var scannerPauses = 0
        val gate = BleAdvFeatureGate(enabled = false)

        val runtime = gate.createRuntime {
            runtimeCreations += 1
            FakeRuntime(onPauseScanning = { scannerPauses += 1 })
        }

        runtime?.pauseScanning()

        assertFalse(gate.exposesEntitiesAndServices)
        assertNull(runtime)
        assertEquals(0, runtimeCreations)
        assertEquals(0, scannerPauses)
    }

    @Test
    fun `enabled mode exposes api and constructs one radio runtime`() {
        var runtimeCreations = 0
        val gate = BleAdvFeatureGate(enabled = true)

        val runtime = gate.createRuntime {
            runtimeCreations += 1
            FakeRuntime(onPauseScanning = {})
        }

        assertTrue(gate.exposesEntitiesAndServices)
        assertEquals(1, runtimeCreations)
        assertTrue(runtime != null)
    }

    private class FakeRuntime(private val onPauseScanning: () -> Unit) {
        fun pauseScanning() = onPauseScanning()
    }
}
