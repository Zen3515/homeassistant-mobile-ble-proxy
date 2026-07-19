package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTeardownGuardTest {
    @Test
    fun `connection teardown can only be finalized once`() {
        val guard = ConnectionTeardownGuard()

        assertTrue(guard.tryFinalize())
        assertFalse(guard.tryFinalize())
        assertFalse(guard.tryFinalize())
    }
}
