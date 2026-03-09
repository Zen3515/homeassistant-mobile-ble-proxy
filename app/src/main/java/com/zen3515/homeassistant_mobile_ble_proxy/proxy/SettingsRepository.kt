package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "proxy_settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val nodeName = stringPreferencesKey("node_name")
        val friendlyName = stringPreferencesKey("friendly_name")
        val apiPort = intPreferencesKey("api_port")
        val bluetoothMacOverride = stringPreferencesKey("bluetooth_mac_override")
        val espHomeApiEncryptionKey = stringPreferencesKey("esphome_api_encryption_key")
        val verboseGattNotifyDataLogging = booleanPreferencesKey("verbose_gatt_notify_data_logging")
        val autoStartOnBoot = booleanPreferencesKey("auto_start_on_boot")
        val scannerMode = stringPreferencesKey("scanner_mode")
        val advertisementFlushIntervalMs = intPreferencesKey("advertisement_flush_interval_ms")
        val advertisementDedupWindowMs = intPreferencesKey("advertisement_dedup_window_ms")
        val advertisementDiscoveryThrottleIntervalMs = intPreferencesKey("advertisement_discovery_throttle_interval_ms")
        val scannerHealthCheckIntervalMs = intPreferencesKey("scanner_health_check_interval_ms")
        val scannerLowRateConsecutiveChecks = intPreferencesKey("scanner_low_rate_consecutive_checks")
        val nsdInterfaceMode = stringPreferencesKey("nsd_interface_mode")
        val advertisementFilters = stringPreferencesKey("advertisement_filters")
        val autoAddMatchedDevicesToLockScreenTargets = booleanPreferencesKey(
            "auto_add_matched_devices_to_lock_screen_targets",
        )
        val managedTargetDevices = stringPreferencesKey("managed_target_devices")
    }

    val settings: Flow<ProxySettings> = context.dataStore.data.map { prefs ->
        val generatedMac = ProxyIdentity.stableMacAddress(context)
        val generatedNoisePsk = ProxyIdentity.stableNoisePsk(context)

        ProxySettings(
            nodeName = prefs[Keys.nodeName] ?: ProxySettings().nodeName,
            friendlyName = prefs[Keys.friendlyName] ?: ProxySettings().friendlyName,
            apiPort = (prefs[Keys.apiPort] ?: ProxySettings().apiPort).coerceIn(1024, 65535),
            bluetoothMacOverride = prefs[Keys.bluetoothMacOverride].takeUnless { it.isNullOrBlank() } ?: generatedMac,
            espHomeApiEncryptionKey = prefs[Keys.espHomeApiEncryptionKey] ?: generatedNoisePsk,
            verboseGattNotifyDataLogging = prefs[Keys.verboseGattNotifyDataLogging] ?: ProxySettings().verboseGattNotifyDataLogging,
            autoStartOnBoot = prefs[Keys.autoStartOnBoot] ?: false,
            scannerMode = ScannerMode.entries.firstOrNull {
                it.name == (prefs[Keys.scannerMode] ?: ScannerMode.PASSIVE.name)
            } ?: ScannerMode.PASSIVE,
            advertisementFlushIntervalMs = (
                prefs[Keys.advertisementFlushIntervalMs] ?: ProxySettings().advertisementFlushIntervalMs
            ).coerceIn(50, 10_000),
            advertisementDedupWindowMs = (
                prefs[Keys.advertisementDedupWindowMs] ?: ProxySettings().advertisementDedupWindowMs
            ).coerceIn(0, 60_000),
            advertisementDiscoveryThrottleIntervalMs = (
                prefs[Keys.advertisementDiscoveryThrottleIntervalMs] ?: ProxySettings().advertisementDiscoveryThrottleIntervalMs
            ).coerceIn(0, 3_600_000),
            scannerHealthCheckIntervalMs = (
                prefs[Keys.scannerHealthCheckIntervalMs] ?: ProxySettings().scannerHealthCheckIntervalMs
            ).coerceIn(5_000, 120_000),
            scannerLowRateConsecutiveChecks = (
                prefs[Keys.scannerLowRateConsecutiveChecks] ?: ProxySettings().scannerLowRateConsecutiveChecks
            ).coerceIn(1, 12),
            nsdInterfaceMode = NsdInterfaceMode.entries.firstOrNull {
                it.name == (prefs[Keys.nsdInterfaceMode] ?: ProxySettings().nsdInterfaceMode.name)
            } ?: NsdInterfaceMode.AUTO,
            advertisementFilters = decodeAdvertisementFilters(prefs[Keys.advertisementFilters]),
            autoAddMatchedDevicesToLockScreenTargets = prefs[Keys.autoAddMatchedDevicesToLockScreenTargets] ?: false,
            managedTargetDevices = decodeManagedTargetDevices(prefs[Keys.managedTargetDevices]),
        )
    }

    suspend fun save(settings: ProxySettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.nodeName] = settings.nodeName
            prefs[Keys.friendlyName] = settings.friendlyName
            prefs[Keys.apiPort] = settings.apiPort
            prefs[Keys.bluetoothMacOverride] = settings.bluetoothMacOverride
            prefs[Keys.espHomeApiEncryptionKey] = settings.espHomeApiEncryptionKey
            prefs[Keys.verboseGattNotifyDataLogging] = settings.verboseGattNotifyDataLogging
            prefs[Keys.autoStartOnBoot] = settings.autoStartOnBoot
            prefs[Keys.scannerMode] = settings.scannerMode.name
            prefs[Keys.advertisementFlushIntervalMs] = settings.advertisementFlushIntervalMs
            prefs[Keys.advertisementDedupWindowMs] = settings.advertisementDedupWindowMs
            prefs[Keys.advertisementDiscoveryThrottleIntervalMs] = settings.advertisementDiscoveryThrottleIntervalMs
            prefs[Keys.scannerHealthCheckIntervalMs] = settings.scannerHealthCheckIntervalMs
            prefs[Keys.scannerLowRateConsecutiveChecks] = settings.scannerLowRateConsecutiveChecks
            prefs[Keys.nsdInterfaceMode] = settings.nsdInterfaceMode.name
            prefs[Keys.advertisementFilters] = encodeAdvertisementFilters(settings.advertisementFilters)
            prefs[Keys.autoAddMatchedDevicesToLockScreenTargets] = settings.autoAddMatchedDevicesToLockScreenTargets
            prefs[Keys.managedTargetDevices] = encodeManagedTargetDevices(settings.managedTargetDevices)
        }
    }

    private fun decodeAdvertisementFilters(serialized: String?): List<AdvertisementFilterRule> {
        if (serialized.isNullOrBlank()) {
            return emptyList()
        }

        return runCatching {
            val jsonArray = JSONArray(serialized)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    val item = jsonArray.optJSONObject(index) ?: continue
                    val id = item.optString("id").ifBlank { "rule_$index" }
                    add(
                        AdvertisementFilterRule(
                            id = id,
                            enabled = item.optBoolean("enabled", true),
                            macRegex = item.optString("macRegex"),
                            nameRegex = item.optString("nameRegex"),
                            minRssi = item.optInt("minRssi", -127).coerceIn(-127, 0),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeAdvertisementFilters(filters: List<AdvertisementFilterRule>): String {
        val array = JSONArray()
        for (rule in filters) {
            array.put(
                JSONObject()
                    .put("id", rule.id)
                    .put("enabled", rule.enabled)
                    .put("macRegex", rule.macRegex)
                    .put("nameRegex", rule.nameRegex)
                    .put("minRssi", rule.minRssi),
            )
        }
        return array.toString()
    }

    private fun decodeManagedTargetDevices(json: String?): List<ManagedTargetDevice> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            val targets = mutableListOf<ManagedTargetDevice>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                targets.add(
                    ManagedTargetDevice(
                        id = obj.getString("id"),
                        macAddress = obj.optString("mac_address", ""),
                        name = obj.optString("name", ""),
                        enableLockScreenScan = obj.optBoolean("enable_lock_screen_scan", true),
                        enableAutoPair = obj.optBoolean("enable_auto_pair", false),
                    ),
                )
            }
            targets
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun encodeManagedTargetDevices(targets: List<ManagedTargetDevice>): String {
        val array = JSONArray()
        targets.forEach { target ->
            val obj = JSONObject().apply {
                put("id", target.id)
                put("mac_address", target.macAddress)
                put("name", target.name)
                put("enable_lock_screen_scan", target.enableLockScreenScan)
                put("enable_auto_pair", target.enableAutoPair)
            }
            array.put(obj)
        }
        return array.toString()
    }
}
