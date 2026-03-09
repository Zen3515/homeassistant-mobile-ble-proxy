package com.zen3515.homeassistant_mobile_ble_proxy.proxy

enum class ScannerMode {
    PASSIVE,
    ACTIVE,
}

enum class NsdInterfaceMode {
    AUTO,
    WIFI,
    CELLULAR,
    VPN,
    DISABLED,
}

data class AdvertisementFilterRule(
    val id: String,
    val enabled: Boolean = true,
    val macRegex: String = "",
    val nameRegex: String = "",
    val minRssi: Int = -127,
)

data class ManagedTargetDevice(
    val id: String,
    val macAddress: String = "",
    val name: String = "",
    val enableLockScreenScan: Boolean = true,
    val enableAutoPair: Boolean = false,
)

data class ProxySettings(
    val nodeName: String = "android_ble_proxy",
    val friendlyName: String = "Android BLE Proxy",
    val apiPort: Int = 6053,
    val bluetoothMacOverride: String = "",
    val espHomeApiEncryptionKey: String = "",
    val verboseGattNotifyDataLogging: Boolean = false,
    val autoStartOnBoot: Boolean = false,
    val scannerMode: ScannerMode = ScannerMode.PASSIVE,
    val advertisementFlushIntervalMs: Int = 100,
    val advertisementDedupWindowMs: Int = 10_000,
    val advertisementDiscoveryThrottleIntervalMs: Int = 10_000,
    val scannerHealthCheckIntervalMs: Int = 10_000,
    val scannerLowRateConsecutiveChecks: Int = 3,
    val nsdInterfaceMode: NsdInterfaceMode = NsdInterfaceMode.AUTO,
    val advertisementFilters: List<AdvertisementFilterRule> = emptyList(),
    val autoAddMatchedDevicesToLockScreenTargets: Boolean = false,
    val managedTargetDevices: List<ManagedTargetDevice> = emptyList(),
)
