package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EspHomeCompatibilityTest {
    @Test
    fun `compatibility metadata is pinned to the audited upstream revision`() {
        assertEquals("96dd2382c14fbf2d6219ef354bc6e40b5ff0f0b4", EspHomeCompatibility.UPSTREAM_COMMIT)
        assertEquals("2026.8.0-dev-android", EspHomeCompatibility.VERSION)
        assertEquals(1, EspHomeCompatibility.API_VERSION_MAJOR)
        assertEquals(14, EspHomeCompatibility.API_VERSION_MINOR)
        assertTrue(EspHomeCompatibility.VERSION.length <= 32)
    }

    @Test
    fun `mdns uses the shared ESPHome compatibility version`() {
        val attributes = buildEspHomeMdnsTxtAttributes(
            macAddress = "AA:BB:CC:DD:EE:FF",
            network = "wifi",
        )

        assertEquals(EspHomeCompatibility.VERSION, attributes["version"])
        assertEquals("AA:BB:CC:DD:EE:FF", attributes["mac"])
        assertEquals("ESP32", attributes["platform"])
        assertEquals("android", attributes["board"])
        assertEquals("wifi", attributes["network"])
    }

    @Test
    fun `connection parameter messages are known but unsupported bit is off by default`() {
        assertEquals(145, EspHomeMessageType.BLUETOOTH_SET_CONNECTION_PARAMS_REQUEST)
        assertEquals(146, EspHomeMessageType.BLUETOOTH_SET_CONNECTION_PARAMS_RESPONSE)
        assertEquals(128, BluetoothProxyFeatureFlags.FEATURE_CONNECTION_PARAMS_SETTING)
        assertEquals(
            0,
            BluetoothProxyFeatureFlags.ACTIVE_FEATURE_FLAGS and
                BluetoothProxyFeatureFlags.FEATURE_CONNECTION_PARAMS_SETTING,
        )
        assertEquals(
            BluetoothProxyFeatureFlags.FEATURE_CONNECTION_PARAMS_SETTING,
            BluetoothProxyFeatureFlags.activeFeatureFlags(connectionParamsSupported = true) and
                BluetoothProxyFeatureFlags.FEATURE_CONNECTION_PARAMS_SETTING,
        )
    }
}
