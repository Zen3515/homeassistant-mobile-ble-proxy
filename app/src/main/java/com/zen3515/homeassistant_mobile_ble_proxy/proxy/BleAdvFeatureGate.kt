package com.zen3515.homeassistant_mobile_ble_proxy.proxy

/**
 * Single opt-in gate for the complete ble_adv runtime and its ESPHome API exposure.
 * Keeping runtime construction behind this gate guarantees that disabled mode cannot transmit
 * advertisements or invoke the scanner pause hooks.
 */
internal class BleAdvFeatureGate(private val enabled: Boolean) {
    val exposesEntitiesAndServices: Boolean
        get() = enabled

    fun <T : Any> createRuntime(factory: () -> T): T? {
        return if (enabled) factory() else null
    }
}
