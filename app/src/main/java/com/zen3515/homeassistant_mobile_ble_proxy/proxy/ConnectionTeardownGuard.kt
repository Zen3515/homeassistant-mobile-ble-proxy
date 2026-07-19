package com.zen3515.homeassistant_mobile_ble_proxy.proxy

/** Ensures connection teardown side effects are emitted at most once. */
internal class ConnectionTeardownGuard {
    private var finalized = false

    fun tryFinalize(): Boolean {
        if (finalized) return false
        finalized = true
        return true
    }
}
