package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyIdentityTest {

    @Test
    fun `normalizeMacAddress accepts common formats`() {
        assertEquals("AA:BB:CC:DD:EE:FF", ProxyIdentity.normalizeMacAddress("aa:bb:cc:dd:ee:ff"))
        assertEquals("AA:BB:CC:DD:EE:FF", ProxyIdentity.normalizeMacAddress("aa-bb-cc-dd-ee-ff"))
        assertEquals("AA:BB:CC:DD:EE:FF", ProxyIdentity.normalizeMacAddress("aabbccddeeff"))
    }

    @Test
    fun `normalizeMacAddress rejects invalid values`() {
        assertNull(ProxyIdentity.normalizeMacAddress(""))
        assertNull(ProxyIdentity.normalizeMacAddress("aa:bb:cc"))
        assertNull(ProxyIdentity.normalizeMacAddress("zz:bb:cc:dd:ee:ff"))
    }

    @Test
    fun `macToLong converts canonical mac`() {
        assertEquals(0xAABBCCDDEEFFL, ProxyIdentity.macToLong("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `sanitizeServiceName strips unsupported chars and limits length`() {
        assertEquals("android_ble_proxy", ProxyIdentity.sanitizeServiceName(""))
        assertEquals("proxy_name_1", ProxyIdentity.sanitizeServiceName("proxy name#1"))

        val longName = "a".repeat(100)
        assertEquals(63, ProxyIdentity.sanitizeServiceName(longName).length)
    }
}

