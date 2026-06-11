package com.zen3515.homeassistant_mobile_ble_proxy.proxy

data class NsdEndpointCandidate<T>(
    val endpoint: T,
    val transports: Set<NsdAdvertiseTransport>,
    val isActiveNetwork: Boolean,
)

object NsdEndpointSelectionPolicy {
    fun <T> chooseEndpoint(
        mode: NsdInterfaceMode,
        transportOrder: List<NsdAdvertiseTransport>,
        candidates: List<NsdEndpointCandidate<T>>,
    ): T? {
        if (mode == NsdInterfaceMode.DISABLED || candidates.isEmpty()) {
            return null
        }

        return when (mode) {
            NsdInterfaceMode.AUTO -> chooseFrom(candidates)
            NsdInterfaceMode.PREFERRED -> choosePreferredTransport(
                transportOrder = NsdAdvertiseDefaults.sanitizeTransportOrder(transportOrder),
                candidates = candidates,
            )
            NsdInterfaceMode.WIFI -> chooseTransport(NsdAdvertiseTransport.WIFI, candidates)
            NsdInterfaceMode.CELLULAR -> chooseTransport(NsdAdvertiseTransport.CELLULAR, candidates)
            NsdInterfaceMode.VPN -> chooseTransport(NsdAdvertiseTransport.VPN, candidates)
            NsdInterfaceMode.DISABLED -> null
        }
    }

    private fun <T> choosePreferredTransport(
        transportOrder: List<NsdAdvertiseTransport>,
        candidates: List<NsdEndpointCandidate<T>>,
    ): T? {
        for (transport in transportOrder) {
            val match = chooseTransport(transport, candidates)
            if (match != null) {
                return match
            }
        }
        return null
    }

    private fun <T> chooseTransport(
        transport: NsdAdvertiseTransport,
        candidates: List<NsdEndpointCandidate<T>>,
    ): T? {
        return chooseFrom(candidates.filter { transport in it.transports })
    }

    private fun <T> chooseFrom(candidates: List<NsdEndpointCandidate<T>>): T? {
        return candidates.firstOrNull { it.isActiveNetwork }?.endpoint ?: candidates.firstOrNull()?.endpoint
    }
}
