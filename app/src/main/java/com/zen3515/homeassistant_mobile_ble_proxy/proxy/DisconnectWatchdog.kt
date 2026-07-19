package com.zen3515.homeassistant_mobile_ble_proxy.proxy

/** A cancellable one-shot watchdog that ignores callbacks delivered after cancellation. */
internal class DisconnectWatchdog(
    private val timeoutMs: Long,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallback: (Runnable) -> Unit,
) {
    private var pending: Runnable? = null

    val isArmed: Boolean
        get() = pending != null

    fun arm(onTimeout: () -> Unit) {
        cancel()
        lateinit var scheduled: Runnable
        scheduled = Runnable {
            if (pending !== scheduled) return@Runnable
            pending = null
            onTimeout()
        }
        pending = scheduled
        try {
            postDelayed(scheduled, timeoutMs)
        } catch (throwable: Throwable) {
            pending = null
            throw throwable
        }
    }

    fun cancel() {
        val scheduled = pending ?: return
        pending = null
        removeCallback(scheduled)
    }
}
