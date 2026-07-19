package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisconnectWatchdogTest {
    @Test
    fun `watchdog fires once and disarms itself`() {
        val scheduled = mutableListOf<Runnable>()
        var timeoutCount = 0
        val watchdog = DisconnectWatchdog(
            timeoutMs = 10_000,
            postDelayed = { callback, _ -> scheduled += callback },
            removeCallback = { scheduled -= it },
        )

        watchdog.arm { timeoutCount += 1 }
        assertTrue(watchdog.isArmed)

        scheduled.single().run()
        scheduled.single().run()

        assertEquals(1, timeoutCount)
        assertFalse(watchdog.isArmed)
    }

    @Test
    fun `cancelled or replaced callbacks cannot finalize a connection`() {
        val scheduled = mutableListOf<Runnable>()
        var timeoutCount = 0
        val watchdog = DisconnectWatchdog(
            timeoutMs = 10_000,
            postDelayed = { callback, _ -> scheduled += callback },
            removeCallback = { scheduled -= it },
        )

        watchdog.arm { timeoutCount += 1 }
        val replacedCallback = scheduled.single()
        watchdog.arm { timeoutCount += 10 }
        val activeCallback = scheduled.single()

        replacedCallback.run()
        assertEquals(0, timeoutCount)
        assertTrue(watchdog.isArmed)

        watchdog.cancel()
        activeCallback.run()
        assertEquals(0, timeoutCount)
        assertFalse(watchdog.isArmed)
    }
}
