package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxySettingsJsonCodecTest {
    private val validNoiseKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="

    @Test
    fun `toJson and fromJson round trip full settings`() {
        val settings = ProxySettings(
            nodeName = "garage_ble_proxy",
            friendlyName = "Garage BLE Proxy",
            apiPort = 6123,
            bluetoothMacOverride = "AA:BB:CC:DD:EE:FF",
            espHomeApiEncryptionKey = validNoiseKey,
            verboseGattNotifyDataLogging = true,
            autoStartOnBoot = true,
            scannerMode = ScannerMode.ACTIVE,
            advertisementFlushIntervalMs = 250,
            advertisementDedupWindowMs = 5_000,
            advertisementDiscoveryThrottleIntervalMs = 30_000,
            scannerHealthCheckIntervalMs = 15_000,
            scannerLowRateConsecutiveChecks = 5,
            nsdInterfaceMode = NsdInterfaceMode.VPN,
            advertisementFilters = listOf(
                AdvertisementFilterRule(
                    id = "rule-1",
                    enabled = true,
                    macRegex = "^AA:BB:.*",
                    nameRegex = "Sensor",
                    minRssi = -70,
                ),
            ),
            autoAddMatchedDevicesToLockScreenTargets = true,
            managedTargetDevices = listOf(
                ManagedTargetDevice(
                    id = "target-1",
                    macAddress = "11:22:33:44:55:66",
                    name = "Beacon",
                    enableLockScreenScan = true,
                    enableAutoPair = true,
                ),
            ),
        )

        val encoded = ProxySettingsJsonCodec.toJson(settings)
        val decoded = ProxySettingsJsonCodec.fromJson(encoded)

        assertEquals(settings, decoded)
    }

    @Test
    fun `fromJson rejects unsupported schema version`() {
        val json = """
            {
              "schemaVersion": 99,
              "settings": {
                "nodeName": "proxy"
              }
            }
        """.trimIndent()

        val error = runCatching {
            ProxySettingsJsonCodec.fromJson(json)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("Unsupported config schema version: 99.", error?.message)
    }

    @Test
    fun `fromJson rejects plain text`() {
        val error = runCatching {
            ProxySettingsJsonCodec.fromJson("not a json file")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("Config JSON is invalid.", error?.message)
    }

    @Test
    fun `fromJson rejects unrelated json object`() {
        val json = """
            {
              "foo": "bar"
            }
        """.trimIndent()

        val error = runCatching {
            ProxySettingsJsonCodec.fromJson(json)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("Config JSON must contain schemaVersion and settings.", error?.message)
    }

    @Test
    fun `fromJson rejects missing settings object`() {
        val json = """
            {
              "schemaVersion": 1
            }
        """.trimIndent()

        val error = runCatching {
            ProxySettingsJsonCodec.fromJson(json)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("Config JSON must contain a settings object.", error?.message)
    }

    @Test
    fun `fromJson rejects api port with wrong type`() {
        val json = """
            {
              "schemaVersion": 1,
              "settings": {
                "apiPort": "6053"
              }
            }
        """.trimIndent()

        val error = runCatching {
            ProxySettingsJsonCodec.fromJson(json)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("apiPort must be an integer in 1024..65535.", error?.message)
    }

    @Test
    fun `fromJson rejects invalid esphome key`() {
        val json = """
            {
              "schemaVersion": 1,
              "settings": {
                "espHomeApiEncryptionKey": "***"
              }
            }
        """.trimIndent()

        val error = runCatching {
            ProxySettingsJsonCodec.fromJson(json)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("espHomeApiEncryptionKey must be empty or valid base64.", error?.message)
    }

    @Test
    fun `fromJson rejects esphome key with wrong byte length`() {
        val json = """
            {
              "schemaVersion": 1,
              "settings": {
                "espHomeApiEncryptionKey": "abc"
              }
            }
        """.trimIndent()

        val error = runCatching {
            ProxySettingsJsonCodec.fromJson(json)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("espHomeApiEncryptionKey must decode to exactly 32 bytes.", error?.message)
    }

    @Test
    fun `fromJson rejects invalid filter regex`() {
        val json = """
            {
              "schemaVersion": 1,
              "settings": {
                "advertisementFilters": [
                  {
                    "macRegex": "("
                  }
                ]
              }
            }
        """.trimIndent()

        val error = runCatching {
            ProxySettingsJsonCodec.fromJson(json)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("advertisementFilters[0].macRegex is invalid.", error?.message)
    }

    @Test
    fun `fromJson rejects invalid managed target mac`() {
        val json = """
            {
              "schemaVersion": 1,
              "settings": {
                "managedTargetDevices": [
                  {
                    "macAddress": "zz:11:22:33:44:55"
                  }
                ]
              }
            }
        """.trimIndent()

        val error = runCatching {
            ProxySettingsJsonCodec.fromJson(json)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(
            "managedTargetDevices[0].macAddress must be a valid MAC address.",
            error?.message,
        )
    }

    @Test
    fun `fromJson fills missing fields with defaults`() {
        val json = """
            {
              "schemaVersion": 1,
              "settings": {
                "nodeName": "imported_proxy"
              }
            }
        """.trimIndent()

        val decoded = ProxySettingsJsonCodec.fromJson(json)

        assertEquals(
            ProxySettings(nodeName = "imported_proxy"),
            decoded,
        )
    }
}
