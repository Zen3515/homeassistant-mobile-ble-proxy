package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NsdEndpointSelectionPolicyTest {
    @Test
    fun `preferred mode chooses vpn before active wifi`() {
        val selected = NsdEndpointSelectionPolicy.chooseEndpoint(
            mode = NsdInterfaceMode.PREFERRED,
            transportOrder = listOf(NsdAdvertiseTransport.VPN, NsdAdvertiseTransport.WIFI),
            candidates = listOf(
                candidate("wifi", NsdAdvertiseTransport.WIFI, isActive = true),
                candidate("vpn", NsdAdvertiseTransport.VPN, isActive = false),
            ),
        )

        assertEquals("vpn", selected)
    }

    @Test
    fun `preferred mode falls back to wifi when vpn disappears`() {
        val selected = NsdEndpointSelectionPolicy.chooseEndpoint(
            mode = NsdInterfaceMode.PREFERRED,
            transportOrder = listOf(NsdAdvertiseTransport.VPN, NsdAdvertiseTransport.WIFI),
            candidates = listOf(
                candidate("wifi", NsdAdvertiseTransport.WIFI, isActive = true),
            ),
        )

        assertEquals("wifi", selected)
    }

    @Test
    fun `preferred mode moves back to vpn when it returns`() {
        val selected = NsdEndpointSelectionPolicy.chooseEndpoint(
            mode = NsdInterfaceMode.PREFERRED,
            transportOrder = listOf(NsdAdvertiseTransport.VPN, NsdAdvertiseTransport.WIFI),
            candidates = listOf(
                candidate("wifi", NsdAdvertiseTransport.WIFI, isActive = true),
                candidate("vpn", NsdAdvertiseTransport.VPN, isActive = true),
            ),
        )

        assertEquals("vpn", selected)
    }

    @Test
    fun `wifi only mode does not choose vpn`() {
        val selected = NsdEndpointSelectionPolicy.chooseEndpoint(
            mode = NsdInterfaceMode.WIFI,
            transportOrder = NsdAdvertiseDefaults.transportOrder,
            candidates = listOf(
                candidate("vpn", NsdAdvertiseTransport.VPN, isActive = true),
            ),
        )

        assertNull(selected)
    }

    @Test
    fun `preferred mode honors transports removed from the order`() {
        val selected = NsdEndpointSelectionPolicy.chooseEndpoint(
            mode = NsdInterfaceMode.PREFERRED,
            transportOrder = listOf(NsdAdvertiseTransport.WIFI),
            candidates = listOf(
                candidate("vpn", NsdAdvertiseTransport.VPN, isActive = true),
                candidate("wifi", NsdAdvertiseTransport.WIFI, isActive = false),
            ),
        )

        assertEquals("wifi", selected)
    }

    @Test
    fun `auto mode keeps active network preference`() {
        val selected = NsdEndpointSelectionPolicy.chooseEndpoint(
            mode = NsdInterfaceMode.AUTO,
            transportOrder = NsdAdvertiseDefaults.transportOrder,
            candidates = listOf(
                candidate("wifi", NsdAdvertiseTransport.WIFI, isActive = false),
                candidate("cellular", NsdAdvertiseTransport.CELLULAR, isActive = true),
            ),
        )

        assertEquals("cellular", selected)
    }

    private fun candidate(
        endpoint: String,
        transport: NsdAdvertiseTransport,
        isActive: Boolean,
    ): NsdEndpointCandidate<String> {
        return NsdEndpointCandidate(
            endpoint = endpoint,
            transports = setOf(transport),
            isActiveNetwork = isActive,
        )
    }
}
