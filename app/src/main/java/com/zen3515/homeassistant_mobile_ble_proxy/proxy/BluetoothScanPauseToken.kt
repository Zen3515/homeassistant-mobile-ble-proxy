package com.zen3515.homeassistant_mobile_ble_proxy.proxy

/** Immutable snapshot of the scanner session that an advertising burst temporarily stopped. */
internal data class BluetoothScanPauseToken(
    val wasRunning: Boolean,
    val profile: ScanProfile,
    val mode: ScannerMode,
)
