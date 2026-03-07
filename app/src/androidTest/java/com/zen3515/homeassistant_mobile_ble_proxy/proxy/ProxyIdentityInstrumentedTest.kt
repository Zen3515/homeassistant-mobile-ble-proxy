package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProxyIdentityInstrumentedTest {

    @Test
    fun stableGeneratedIdentityValuesArePersistentAndValid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val mac1 = ProxyIdentity.stableMacAddress(context)
        val mac2 = ProxyIdentity.stableMacAddress(context)
        assertEquals(mac1, mac2)
        assertTrue(mac1.matches(Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")))

        val key1 = ProxyIdentity.stableNoisePsk(context)
        val key2 = ProxyIdentity.stableNoisePsk(context)
        assertEquals(key1, key2)

        val decoded = Base64.decode(key1, Base64.DEFAULT)
        assertEquals(32, decoded.size)
    }
}

