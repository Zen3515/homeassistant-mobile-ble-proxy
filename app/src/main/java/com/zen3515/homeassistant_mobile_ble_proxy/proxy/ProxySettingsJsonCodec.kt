package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import org.bouncycastle.util.encoders.Base64
import org.json.JSONArray
import org.json.JSONObject

object ProxySettingsJsonCodec {
    private const val SCHEMA_VERSION = 1

    fun toJson(settings: ProxySettings): String {
        val root = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("settings", encodeSettings(settings))
        return root.toString(2)
    }

    fun fromJson(json: String): ProxySettings {
        require(json.isNotBlank()) { "Config JSON is empty." }

        val root = try {
            JSONObject(json)
        } catch (error: Exception) {
            throw IllegalArgumentException("Config JSON is invalid.", error)
        }

        if (!root.has("schemaVersion")) {
            throw IllegalArgumentException("Config JSON must contain schemaVersion and settings.")
        }
        val schemaVersion = readRequiredInt(
            json = root,
            fieldName = "schemaVersion",
            validRange = 1..Int.MAX_VALUE,
        )
        require(schemaVersion == SCHEMA_VERSION) {
            "Unsupported config schema version: $schemaVersion."
        }
        val settingsObject = root.optJSONObject("settings")
            ?: throw IllegalArgumentException("Config JSON must contain a settings object.")

        return decodeSettings(settingsObject)
    }

    private fun encodeSettings(settings: ProxySettings): JSONObject {
        return JSONObject()
            .put("nodeName", settings.nodeName)
            .put("friendlyName", settings.friendlyName)
            .put("apiPort", settings.apiPort)
            .put("bluetoothMacOverride", settings.bluetoothMacOverride)
            .put("espHomeApiEncryptionKey", settings.espHomeApiEncryptionKey)
            .put("verboseGattNotifyDataLogging", settings.verboseGattNotifyDataLogging)
            .put("bleAdvProxyEnabled", settings.bleAdvProxyEnabled)
            .put("autoStartOnBoot", settings.autoStartOnBoot)
            .put("scannerMode", settings.scannerMode.name)
            .put("advertisementFlushIntervalMs", settings.advertisementFlushIntervalMs)
            .put("advertisementDedupWindowMs", settings.advertisementDedupWindowMs)
            .put(
                "advertisementDiscoveryThrottleIntervalMs",
                settings.advertisementDiscoveryThrottleIntervalMs,
            )
            .put("scannerHealthCheckIntervalMs", settings.scannerHealthCheckIntervalMs)
            .put("scannerLowRateConsecutiveChecks", settings.scannerLowRateConsecutiveChecks)
            .put("nsdInterfaceMode", settings.nsdInterfaceMode.name)
            .put("nsdTransportOrder", encodeNsdTransportOrder(settings.nsdTransportOrder))
            .put("advertisementFilters", encodeAdvertisementFilters(settings.advertisementFilters))
            .put(
                "autoAddMatchedDevicesToLockScreenTargets",
                settings.autoAddMatchedDevicesToLockScreenTargets,
            )
            .put("managedTargetDevices", encodeManagedTargetDevices(settings.managedTargetDevices))
    }

    private fun decodeSettings(json: JSONObject): ProxySettings {
        val defaults = ProxySettings()
        val bluetoothMacOverride = readOptionalString(
            json = json,
            fieldName = "bluetoothMacOverride",
            defaultValue = defaults.bluetoothMacOverride,
        ).trim()
        val normalizedBluetoothMacOverride = when {
            bluetoothMacOverride.isBlank() -> ""
            else -> ProxyIdentity.normalizeMacAddress(bluetoothMacOverride)
                ?: throw IllegalArgumentException(
                    "bluetoothMacOverride must be empty or a valid MAC address.",
                )
        }

        val espHomeApiEncryptionKey = readOptionalString(
            json = json,
            fieldName = "espHomeApiEncryptionKey",
            defaultValue = defaults.espHomeApiEncryptionKey,
        ).trim()
        validateNoiseKey(espHomeApiEncryptionKey)

        return ProxySettings(
            nodeName = readOptionalString(
                json = json,
                fieldName = "nodeName",
                defaultValue = defaults.nodeName,
            ),
            friendlyName = readOptionalString(
                json = json,
                fieldName = "friendlyName",
                defaultValue = defaults.friendlyName,
            ),
            apiPort = readOptionalInt(
                json = json,
                fieldName = "apiPort",
                defaultValue = defaults.apiPort,
                validRange = 1024..65535,
            ),
            bluetoothMacOverride = normalizedBluetoothMacOverride,
            espHomeApiEncryptionKey = espHomeApiEncryptionKey,
            verboseGattNotifyDataLogging = readOptionalBoolean(
                json = json,
                fieldName = "verboseGattNotifyDataLogging",
                defaultValue = defaults.verboseGattNotifyDataLogging,
            ),
            bleAdvProxyEnabled = readOptionalBoolean(
                json = json,
                fieldName = "bleAdvProxyEnabled",
                defaultValue = defaults.bleAdvProxyEnabled,
            ),
            autoStartOnBoot = readOptionalBoolean(
                json = json,
                fieldName = "autoStartOnBoot",
                defaultValue = defaults.autoStartOnBoot,
            ),
            scannerMode = readOptionalEnum(
                json = json,
                fieldName = "scannerMode",
                defaultValue = defaults.scannerMode,
            ),
            advertisementFlushIntervalMs = readOptionalInt(
                json = json,
                fieldName = "advertisementFlushIntervalMs",
                defaultValue = defaults.advertisementFlushIntervalMs,
                validRange = 50..10_000,
            ),
            advertisementDedupWindowMs = readOptionalInt(
                json = json,
                fieldName = "advertisementDedupWindowMs",
                defaultValue = defaults.advertisementDedupWindowMs,
                validRange = 0..60_000,
            ),
            advertisementDiscoveryThrottleIntervalMs = readOptionalInt(
                json = json,
                fieldName = "advertisementDiscoveryThrottleIntervalMs",
                defaultValue = defaults.advertisementDiscoveryThrottleIntervalMs,
                validRange = 0..3_600_000,
            ),
            scannerHealthCheckIntervalMs = readOptionalInt(
                json = json,
                fieldName = "scannerHealthCheckIntervalMs",
                defaultValue = defaults.scannerHealthCheckIntervalMs,
                validRange = 5_000..120_000,
            ),
            scannerLowRateConsecutiveChecks = readOptionalInt(
                json = json,
                fieldName = "scannerLowRateConsecutiveChecks",
                defaultValue = defaults.scannerLowRateConsecutiveChecks,
                validRange = 1..12,
            ),
            nsdInterfaceMode = decodeNsdInterfaceMode(json),
            nsdTransportOrder = decodeNsdTransportOrder(json),
            advertisementFilters = decodeAdvertisementFilters(
                readOptionalArray(
                    json = json,
                    fieldName = "advertisementFilters",
                ),
            ),
            autoAddMatchedDevicesToLockScreenTargets = readOptionalBoolean(
                json = json,
                fieldName = "autoAddMatchedDevicesToLockScreenTargets",
                defaultValue = defaults.autoAddMatchedDevicesToLockScreenTargets,
            ),
            managedTargetDevices = decodeManagedTargetDevices(
                readOptionalArray(
                    json = json,
                    fieldName = "managedTargetDevices",
                ),
            ),
        )
    }

    private fun encodeAdvertisementFilters(filters: List<AdvertisementFilterRule>): JSONArray {
        val array = JSONArray()
        filters.forEach { rule ->
            array.put(
                JSONObject()
                    .put("id", rule.id)
                    .put("enabled", rule.enabled)
                    .put("macRegex", rule.macRegex)
                    .put("nameRegex", rule.nameRegex)
                    .put("minRssi", rule.minRssi),
            )
        }
        return array
    }

    private fun encodeNsdTransportOrder(order: List<NsdAdvertiseTransport>): JSONArray {
        val array = JSONArray()
        NsdAdvertiseDefaults.sanitizeTransportOrder(order).forEach { transport ->
            array.put(transport.name)
        }
        return array
    }

    private fun decodeNsdInterfaceMode(json: JSONObject): NsdInterfaceMode {
        val rawMode = readOptionalString(
            json = json,
            fieldName = "nsdInterfaceMode",
            defaultValue = ProxySettings().nsdInterfaceMode.name,
        )
        val decoded = NsdAdvertiseDefaults.decodeInterfaceMode(rawMode)
        if (decoded == NsdInterfaceMode.AUTO && rawMode != NsdInterfaceMode.AUTO.name) {
            val knownName = NsdInterfaceMode.entries.any { it.name == rawMode }
            if (!knownName) {
                throw IllegalArgumentException("nsdInterfaceMode must be one of ${NsdInterfaceMode.entries.map { it.name }}.")
            }
        }
        return decoded
    }

    private fun decodeNsdTransportOrder(json: JSONObject): List<NsdAdvertiseTransport> {
        val array = readOptionalArray(
            json = json,
            fieldName = "nsdTransportOrder",
        ) ?: return if (json.optString("nsdInterfaceMode") == NsdInterfaceMode.VPN.name) {
            listOf(NsdAdvertiseTransport.VPN, NsdAdvertiseTransport.WIFI, NsdAdvertiseTransport.CELLULAR)
        } else {
            NsdAdvertiseDefaults.transportOrder
        }

        val decoded = buildList {
            for (index in 0 until array.length()) {
                val value = array.opt(index) as? String
                    ?: throw IllegalArgumentException("nsdTransportOrder[$index] must be a string.")
                val transport = NsdAdvertiseTransport.entries.firstOrNull { it.name == value }
                    ?: throw IllegalArgumentException(
                        "nsdTransportOrder[$index] must be one of ${NsdAdvertiseTransport.entries.map { it.name }}.",
                    )
                add(transport)
            }
        }
        return NsdAdvertiseDefaults.sanitizeTransportOrder(decoded)
    }

    private fun decodeAdvertisementFilters(array: JSONArray?): List<AdvertisementFilterRule> {
        if (array == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until array.length()) {
                val item = array.opt(index) as? JSONObject
                    ?: throw IllegalArgumentException("advertisementFilters[$index] must be an object.")
                val macRegex = readOptionalString(
                    json = item,
                    fieldName = "macRegex",
                    defaultValue = "",
                )
                if (!isRegexPatternValid(macRegex)) {
                    throw IllegalArgumentException("advertisementFilters[$index].macRegex is invalid.")
                }
                val nameRegex = readOptionalString(
                    json = item,
                    fieldName = "nameRegex",
                    defaultValue = "",
                )
                if (!isRegexPatternValid(nameRegex)) {
                    throw IllegalArgumentException("advertisementFilters[$index].nameRegex is invalid.")
                }
                add(
                    AdvertisementFilterRule(
                        id = readOptionalString(
                            json = item,
                            fieldName = "id",
                            defaultValue = "rule_$index",
                        ).ifBlank { "rule_$index" },
                        enabled = readOptionalBoolean(
                            json = item,
                            fieldName = "enabled",
                            defaultValue = true,
                        ),
                        macRegex = macRegex,
                        nameRegex = nameRegex,
                        minRssi = readOptionalInt(
                            json = item,
                            fieldName = "minRssi",
                            defaultValue = -127,
                            validRange = -127..0,
                        ),
                    ),
                )
            }
        }
    }

    private fun encodeManagedTargetDevices(targets: List<ManagedTargetDevice>): JSONArray {
        val array = JSONArray()
        targets.forEach { target ->
            array.put(
                JSONObject()
                    .put("id", target.id)
                    .put("macAddress", target.macAddress)
                    .put("name", target.name)
                    .put("enableLockScreenScan", target.enableLockScreenScan)
                    .put("enableAutoPair", target.enableAutoPair),
            )
        }
        return array
    }

    private fun decodeManagedTargetDevices(array: JSONArray?): List<ManagedTargetDevice> {
        if (array == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until array.length()) {
                val item = array.opt(index) as? JSONObject
                    ?: throw IllegalArgumentException("managedTargetDevices[$index] must be an object.")
                val rawMacAddress = readOptionalString(
                    json = item,
                    fieldName = "macAddress",
                    defaultValue = "",
                ).trim()
                val normalizedMacAddress = when {
                    rawMacAddress.isBlank() -> ""
                    else -> ProxyIdentity.normalizeMacAddress(rawMacAddress)
                        ?: throw IllegalArgumentException(
                            "managedTargetDevices[$index].macAddress must be a valid MAC address.",
                        )
                }
                val name = readOptionalString(
                    json = item,
                    fieldName = "name",
                    defaultValue = "",
                ).trim()
                if (normalizedMacAddress.isBlank() && name.isBlank()) {
                    throw IllegalArgumentException(
                        "managedTargetDevices[$index] must contain a MAC address or name.",
                    )
                }
                add(
                    ManagedTargetDevice(
                        id = readOptionalString(
                            json = item,
                            fieldName = "id",
                            defaultValue = "target_$index",
                        ).ifBlank { "target_$index" },
                        macAddress = normalizedMacAddress,
                        name = name,
                        enableLockScreenScan = readOptionalBoolean(
                            json = item,
                            fieldName = "enableLockScreenScan",
                            defaultValue = true,
                        ),
                        enableAutoPair = readOptionalBoolean(
                            json = item,
                            fieldName = "enableAutoPair",
                            defaultValue = false,
                        ),
                    ),
                )
            }
        }
    }

    private fun readOptionalArray(json: JSONObject, fieldName: String): JSONArray? {
        if (!json.has(fieldName)) {
            return null
        }
        val value = json.get(fieldName)
        return value as? JSONArray
            ?: throw IllegalArgumentException("$fieldName must be an array.")
    }

    private fun readOptionalString(
        json: JSONObject,
        fieldName: String,
        defaultValue: String,
    ): String {
        if (!json.has(fieldName)) {
            return defaultValue
        }
        val value = json.get(fieldName)
        return value as? String
            ?: throw IllegalArgumentException("$fieldName must be a string.")
    }

    private fun readRequiredInt(
        json: JSONObject,
        fieldName: String,
        validRange: IntRange,
    ): Int {
        if (!json.has(fieldName)) {
            throw IllegalArgumentException("$fieldName must be an integer in $validRange.")
        }
        return readOptionalInt(
            json = json,
            fieldName = fieldName,
            defaultValue = validRange.first,
            validRange = validRange,
        )
    }

    private fun readOptionalInt(
        json: JSONObject,
        fieldName: String,
        defaultValue: Int,
        validRange: IntRange,
    ): Int {
        if (!json.has(fieldName)) {
            return defaultValue
        }
        val value = json.get(fieldName)
        val intValue = when (value) {
            is Int -> value
            is Long -> value.toIntOrNull(fieldName)
            is Number -> {
                val asDouble = value.toDouble()
                if (!asDouble.isFinite() || asDouble % 1.0 != 0.0) {
                    throw IllegalArgumentException("$fieldName must be an integer in $validRange.")
                }
                asDouble.toLong().toIntOrNull(fieldName)
            }
            else -> throw IllegalArgumentException("$fieldName must be an integer in $validRange.")
        }
        if (intValue !in validRange) {
            throw IllegalArgumentException("$fieldName must be an integer in $validRange.")
        }
        return intValue
    }

    private fun Long.toIntOrNull(fieldName: String): Int {
        if (this !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            throw IllegalArgumentException("$fieldName is out of range.")
        }
        return toInt()
    }

    private fun readOptionalBoolean(
        json: JSONObject,
        fieldName: String,
        defaultValue: Boolean,
    ): Boolean {
        if (!json.has(fieldName)) {
            return defaultValue
        }
        val value = json.get(fieldName)
        return value as? Boolean
            ?: throw IllegalArgumentException("$fieldName must be a boolean.")
    }

    private inline fun <reified T : Enum<T>> readOptionalEnum(
        json: JSONObject,
        fieldName: String,
        defaultValue: T,
    ): T {
        val rawValue = readOptionalString(
            json = json,
            fieldName = fieldName,
            defaultValue = defaultValue.name,
        )
        return enumValues<T>().firstOrNull { it.name == rawValue }
            ?: throw IllegalArgumentException(
                "$fieldName must be one of: ${enumValues<T>().joinToString { it.name }}.",
            )
    }

    private fun isRegexPatternValid(pattern: String): Boolean {
        if (pattern.isBlank()) {
            return true
        }
        return runCatching { Regex(pattern) }.isSuccess
    }

    private fun validateNoiseKey(value: String) {
        if (value.isBlank()) {
            return
        }
        val normalized = value.filterNot(Char::isWhitespace)
        val decoded = decodeBase64Compat(normalized) ?: throw IllegalArgumentException(
            "espHomeApiEncryptionKey must be empty or valid base64.",
        )
        if (decoded.size != 32) {
            throw IllegalArgumentException(
                "espHomeApiEncryptionKey must decode to exactly 32 bytes.",
            )
        }
    }

    private fun decodeBase64Compat(value: String): ByteArray? {
        val padded = when (value.length % 4) {
            0 -> value
            2 -> "$value=="
            3 -> "$value="
            else -> return null
        }
        return runCatching {
            Base64.decode(padded)
        }.getOrNull()
    }
}
