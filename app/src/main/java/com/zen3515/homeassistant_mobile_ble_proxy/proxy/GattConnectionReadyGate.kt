package com.zen3515.homeassistant_mobile_ble_proxy.proxy

/**
 * Tracks when an ESPHome Bluetooth connection may be reported as ready.
 *
 * A v3 connection with a client-side cache is ready as soon as Android reports
 * the link connected. A v3 connection without a cache must first complete fresh
 * service discovery; the GATT manager serializes that discovery behind its
 * initial MTU gate.
 */
internal class GattConnectionReadyGate(
    private val requiresFreshServices: Boolean,
) {
    var isReady: Boolean = false
        private set

    val isWaitingForFreshServices: Boolean
        get() = requiresFreshServices && !isReady

    fun onLinkConnected(): Boolean =
        if (requiresFreshServices) {
            false
        } else {
            markReady()
        }

    fun onFreshServicesDiscovered(): Boolean =
        if (requiresFreshServices) {
            markReady()
        } else {
            false
        }

    private fun markReady(): Boolean {
        if (isReady) {
            return false
        }
        isReady = true
        return true
    }
}
