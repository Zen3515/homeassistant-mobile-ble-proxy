package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class EspHomeNsdAdvertiser(
    context: Context,
    private val onError: (String) -> Unit,
    private val onLog: (String) -> Unit = {},
) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var announcerJob: Job? = null
    private var endpointRefreshJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentRegistration: AdvertisementRegistration? = null
    private var activeEndpoint: SelectedEndpoint? = null
    private var activeGoodbyePacket: ByteArray? = null
    private var activeMulticastAddress: InetAddress? = null
    private var activeSocket: MulticastSocket? = null
    private var announcementGeneration: Long = 0

    @Synchronized
    fun register(settings: ProxySettings, macAddress: String, port: Int) {
        unregister()
        if (settings.nsdInterfaceMode == NsdInterfaceMode.DISABLED) {
            onLog("mDNS advertisement disabled by settings")
            return
        }

        currentRegistration = AdvertisementRegistration(settings, macAddress, port)
        registerNetworkCallbackLocked()
        refreshEndpointLocked(reason = "startup")
    }

    @Synchronized
    fun unregister() {
        val callback = networkCallback
        networkCallback = null
        if (callback != null) {
            runCatching {
                connectivityManager?.unregisterNetworkCallback(callback)
            }.onFailure { error ->
                onLog("mDNS network callback unregister failed: ${error.message}")
            }
        }
        endpointRefreshJob?.cancel()
        endpointRefreshJob = null
        currentRegistration = null
        stopActiveAnnouncementLocked()
    }

    fun shutdown() {
        unregister()
        scope.cancel()
    }

    @Synchronized
    private fun refreshEndpointLocked(reason: String) {
        val registration = currentRegistration ?: return
        val settings = registration.settings
        val endpoint = selectEndpoint(
            mode = settings.nsdInterfaceMode,
            transportOrder = settings.nsdTransportOrder,
        )
        if (endpoint == null) {
            if (activeEndpoint != null) {
                onLog("mDNS endpoint unavailable after $reason; stopping current announcement")
                stopActiveAnnouncementLocked()
            }
            return
        }

        if (endpoint == activeEndpoint) {
            return
        }

        if (activeEndpoint != null) {
            onLog(
                "mDNS endpoint changed after $reason: " +
                    "${activeEndpoint?.transportLabel}/${activeEndpoint?.ipv4Address?.hostAddress} -> " +
                    "${endpoint.transportLabel}/${endpoint.ipv4Address.hostAddress}",
            )
        }
        stopActiveAnnouncementLocked()

        val serviceName = ProxyIdentity.sanitizeServiceName(settings.nodeName)
        val hostName = "$serviceName.local"
        val instanceName = "$serviceName.$ESPHOME_SERVICE_TYPE"
        val txtAttributes = linkedMapOf(
            "version" to "2026.3.0",
            "mac" to registration.macAddress,
            "platform" to "ESP32",
            "board" to "android",
            "network" to endpoint.transportLabel,
        )

        // We intentionally bypass Android's NsdManager here:
        // some devices/VPN combinations never deliver registration callbacks,
        // leaving service publish state unknown. Raw mDNS announcements keep behavior deterministic.
        val announcePacket = buildAnnouncementPacket(
            instanceName = instanceName,
            hostName = hostName,
            port = registration.port,
            address = endpoint.ipv4Address,
            txtAttributes = txtAttributes,
            ttlSeconds = RECORD_TTL_SECONDS,
        )
        val goodbyePacket = buildAnnouncementPacket(
            instanceName = instanceName,
            hostName = hostName,
            port = registration.port,
            address = endpoint.ipv4Address,
            txtAttributes = txtAttributes,
            ttlSeconds = 0,
        )
        val multicastAddress = runCatching {
            InetAddress.getByName(MDNS_MULTICAST_IPV4)
        }.getOrElse { error ->
            onError("Unable to resolve mDNS multicast address: ${error.message}")
            return
        }

        onLog(
            "mDNS interface mode: ${settings.nsdInterfaceMode.name.lowercase()} " +
                "(order=${settings.nsdTransportOrder.joinToString(">") { it.name.lowercase() }}, " +
                "network=${endpoint.network}, ip=${endpoint.ipv4Address.hostAddress}, transport=${endpoint.transportLabel})",
        )
        onLog(
            "mDNS registration attempt " +
                "(service=$serviceName, type=$ESPHOME_SERVICE_TYPE, port=${registration.port})",
        )
        startAnnouncer(
            endpoint = endpoint,
            announcePacket = announcePacket,
            goodbyePacket = goodbyePacket,
            multicastAddress = multicastAddress,
            instanceName = instanceName,
            servicePort = registration.port,
        )
    }

    private fun startAnnouncer(
        endpoint: SelectedEndpoint,
        announcePacket: ByteArray,
        goodbyePacket: ByteArray,
        multicastAddress: InetAddress,
        instanceName: String,
        servicePort: Int,
    ) {
        val generation = ++announcementGeneration
        activeEndpoint = endpoint
        activeGoodbyePacket = goodbyePacket
        activeMulticastAddress = multicastAddress
        announcerJob = scope.launch {
            val socket = createSocket(endpoint.network) ?: run {
                synchronized(this@EspHomeNsdAdvertiser) {
                    if (generation == announcementGeneration && activeEndpoint == endpoint) {
                        activeEndpoint = null
                        activeGoodbyePacket = null
                        activeMulticastAddress = null
                        announcerJob = null
                    }
                }
                return@launch
            }
            synchronized(this@EspHomeNsdAdvertiser) {
                if (generation != announcementGeneration || activeEndpoint != endpoint) {
                    socket.close()
                    return@launch
                }
                activeSocket = socket
            }
            try {
                repeat(INITIAL_ANNOUNCE_BURST_COUNT) { index ->
                    sendPacket(socket, announcePacket, multicastAddress)
                    if (index == 0) {
                        onLog(
                            "mDNS service announced as $instanceName " +
                                "(type=$ESPHOME_SERVICE_TYPE, port=$servicePort)",
                        )
                    }
                    if (index < INITIAL_ANNOUNCE_BURST_COUNT - 1) {
                        delay(INITIAL_ANNOUNCE_BURST_INTERVAL_MS)
                    }
                }
                onLog("mDNS announcer running (interval=${PERIODIC_ANNOUNCE_INTERVAL_MS}ms)")
                while (isActive) {
                    delay(PERIODIC_ANNOUNCE_INTERVAL_MS)
                    sendPacket(socket, announcePacket, multicastAddress)
                }
            } catch (_: CancellationException) {
                // Normal shutdown path.
            } catch (error: Throwable) {
                onError(
                    "mDNS announcer stopped " +
                        "(network=${endpoint.network}, ip=${endpoint.ipv4Address.hostAddress}): ${error.message}",
                )
            } finally {
                runCatching {
                    sendPacket(socket, goodbyePacket, multicastAddress)
                }
                runCatching {
                    socket.close()
                }
                synchronized(this@EspHomeNsdAdvertiser) {
                    if (generation == announcementGeneration && activeSocket === socket) {
                        activeSocket = null
                    }
                }
            }
        }
    }

    private fun stopActiveAnnouncementLocked() {
        announcementGeneration += 1
        val socket = activeSocket
        val goodbyePacket = activeGoodbyePacket
        val multicastAddress = activeMulticastAddress
        if (socket != null && goodbyePacket != null && multicastAddress != null) {
            runCatching {
                sendPacket(socket, goodbyePacket, multicastAddress)
            }
        }

        val job = announcerJob
        announcerJob = null
        job?.cancel()
        runCatching {
            activeSocket?.close()
        }.onFailure { error ->
            onError("Unable to close mDNS socket: ${error.message}")
        }
        activeSocket = null
        activeEndpoint = null
        activeGoodbyePacket = null
        activeMulticastAddress = null
    }

    private fun registerNetworkCallbackLocked() {
        val manager = connectivityManager ?: return
        if (networkCallback != null) {
            return
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scheduleEndpointRefresh("network available")
            }

            override fun onLost(network: Network) {
                scheduleEndpointRefresh("network lost")
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                scheduleEndpointRefresh("network capabilities changed")
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                scheduleEndpointRefresh("network link properties changed")
            }
        }
        val requestBuilder = NetworkRequest.Builder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestBuilder.clearCapabilities()
        } else {
            requestBuilder.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            requestBuilder.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
        val request = requestBuilder.build()

        runCatching {
            manager.registerNetworkCallback(request, callback)
            networkCallback = callback
            onLog("mDNS network change watcher registered")
        }.onFailure { error ->
            onLog("mDNS network change watcher unavailable: ${error.message}")
        }
    }

    private fun scheduleEndpointRefresh(reason: String) {
        synchronized(this) {
            if (currentRegistration == null) {
                return
            }
            endpointRefreshJob?.cancel()
            endpointRefreshJob = scope.launch {
                delay(NETWORK_RESELECT_DEBOUNCE_MS)
                synchronized(this@EspHomeNsdAdvertiser) {
                    refreshEndpointLocked(reason)
                }
            }
        }
    }

    private fun createSocket(network: Network): MulticastSocket? {
        return runCatching {
            runCatching {
                MulticastSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(MDNS_PORT))
                    timeToLive = MDNS_PACKET_TTL
                    network.bindSocket(this)
                }
            }.getOrElse {
                onLog("mDNS socket bind to :$MDNS_PORT failed, falling back to ephemeral source port")
                MulticastSocket().apply {
                    reuseAddress = true
                    timeToLive = MDNS_PACKET_TTL
                    network.bindSocket(this)
                }
            }
        }.onFailure { error ->
            onError("Unable to create mDNS socket for network $network: ${error.message}")
        }.getOrNull()
    }

    private fun sendPacket(socket: MulticastSocket, payload: ByteArray, multicastAddress: InetAddress) {
        val packet = DatagramPacket(payload, payload.size, multicastAddress, MDNS_PORT)
        socket.send(packet)
    }

    private fun buildAnnouncementPacket(
        instanceName: String,
        hostName: String,
        port: Int,
        address: Inet4Address,
        txtAttributes: Map<String, String>,
        ttlSeconds: Int,
    ): ByteArray {
        val records = listOf(
            DnsRecord(
                name = DNS_SD_META_QUERY,
                type = DNS_TYPE_PTR,
                klass = DNS_CLASS_IN,
                ttl = ttlSeconds,
                rdata = encodeDnsName(ESPHOME_SERVICE_TYPE),
            ),
            DnsRecord(
                name = ESPHOME_SERVICE_TYPE,
                type = DNS_TYPE_PTR,
                klass = DNS_CLASS_IN,
                ttl = ttlSeconds,
                rdata = encodeDnsName(instanceName),
            ),
            DnsRecord(
                name = instanceName,
                type = DNS_TYPE_SRV,
                klass = DNS_CLASS_IN or DNS_CLASS_CACHE_FLUSH,
                ttl = ttlSeconds,
                rdata = buildSrvRdata(port = port, targetHostName = hostName),
            ),
            DnsRecord(
                name = instanceName,
                type = DNS_TYPE_TXT,
                klass = DNS_CLASS_IN or DNS_CLASS_CACHE_FLUSH,
                ttl = ttlSeconds,
                rdata = buildTxtRdata(txtAttributes),
            ),
            DnsRecord(
                name = hostName,
                type = DNS_TYPE_A,
                klass = DNS_CLASS_IN or DNS_CLASS_CACHE_FLUSH,
                ttl = ttlSeconds,
                rdata = address.address,
            ),
        )

        val out = ByteArrayOutputStream(512)
        val data = DataOutputStream(out)
        data.writeShort(0) // transaction id
        data.writeShort(DNS_FLAGS_RESPONSE_AUTHORITATIVE)
        data.writeShort(0) // questions
        data.writeShort(records.size) // answers
        data.writeShort(0) // authority
        data.writeShort(0) // additional
        records.forEach { record ->
            data.write(encodeDnsName(record.name))
            data.writeShort(record.type and 0xFFFF)
            data.writeShort(record.klass and 0xFFFF)
            data.writeInt(record.ttl)
            data.writeShort(record.rdata.size and 0xFFFF)
            data.write(record.rdata)
        }
        data.flush()
        return out.toByteArray()
    }

    private fun buildSrvRdata(port: Int, targetHostName: String): ByteArray {
        val out = ByteArrayOutputStream(256)
        val data = DataOutputStream(out)
        data.writeShort(0) // priority
        data.writeShort(0) // weight
        data.writeShort(port and 0xFFFF)
        data.write(encodeDnsName(targetHostName))
        data.flush()
        return out.toByteArray()
    }

    private fun buildTxtRdata(attributes: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream(256)
        attributes.forEach { (key, value) ->
            val entry = "$key=$value".toByteArray(StandardCharsets.UTF_8)
            if (entry.isEmpty() || entry.size > 255) {
                return@forEach
            }
            out.write(entry.size)
            out.write(entry)
        }
        return out.toByteArray()
    }

    private fun encodeDnsName(name: String): ByteArray {
        val out = ByteArrayOutputStream(128)
        val normalized = name.trimEnd('.')
        normalized
            .split('.')
            .filter { it.isNotEmpty() }
            .forEach { label ->
                val bytes = label.toByteArray(StandardCharsets.UTF_8)
                out.write(bytes.size)
                out.write(bytes)
            }
        out.write(0)
        return out.toByteArray()
    }

    private fun selectEndpoint(
        mode: NsdInterfaceMode,
        transportOrder: List<NsdAdvertiseTransport>,
    ): SelectedEndpoint? {
        val manager = connectivityManager
        if (manager == null) {
            onError("mDNS unavailable: ConnectivityManager is null")
            return null
        }

        val allNetworks = getAllNetworksCompat(manager)
        if (allNetworks.isEmpty()) {
            onError("mDNS unavailable: no active networks found")
            return null
        }

        val endpoints = allNetworks.mapNotNull { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            val transports = nsdTransports(capabilities)
            val linkProperties = manager.getLinkProperties(network) ?: return@mapNotNull null
            val ipv4Address = linkProperties.linkAddresses
                .asSequence()
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }
                ?: return@mapNotNull null
            SelectedEndpoint(
                network = network,
                ipv4Address = ipv4Address,
                transports = transports,
                transportLabel = transportLabel(capabilities),
            )
        }
        if (endpoints.isEmpty()) {
            onError(
                "mDNS interface mode ${mode.name.lowercase()} selected but no matching network with IPv4 address is available",
            )
            return null
        }

        val activeNetwork = manager.activeNetwork
        val candidates = endpoints.map { endpoint ->
            NsdEndpointCandidate(
                endpoint = endpoint,
                transports = endpoint.transports,
                isActiveNetwork = endpoint.network == activeNetwork,
            )
        }
        return NsdEndpointSelectionPolicy.chooseEndpoint(
            mode = mode,
            transportOrder = transportOrder,
            candidates = candidates,
        ) ?: run {
            onError(
                "mDNS interface mode ${mode.name.lowercase()} selected but no preferred matching network with IPv4 address is available",
            )
            null
        }
    }

    private fun getAllNetworksCompat(manager: ConnectivityManager): List<Network> {
        val method = ConnectivityManager::class.java.getMethod("getAllNetworks")
        val networks = method.invoke(manager) as? Array<*> ?: return emptyList()
        return networks.filterIsInstance<Network>()
    }

    private fun transportLabel(capabilities: NetworkCapabilities): String {
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
    }

    private fun nsdTransports(capabilities: NetworkCapabilities): Set<NsdAdvertiseTransport> {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return setOf(NsdAdvertiseTransport.VPN)
        }
        return buildSet {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                add(NsdAdvertiseTransport.WIFI)
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                add(NsdAdvertiseTransport.CELLULAR)
            }
        }
    }

    companion object {
        @Suppress("SpellCheckingInspection")
        private const val ESPHOME_SERVICE_TYPE = "_esphomelib._tcp.local"
        private const val DNS_SD_META_QUERY = "_services._dns-sd._udp.local"
        private const val MDNS_MULTICAST_IPV4 = "224.0.0.251"
        private const val MDNS_PORT = 5353
        private const val MDNS_PACKET_TTL = 255
        private const val RECORD_TTL_SECONDS = 120
        private const val INITIAL_ANNOUNCE_BURST_COUNT = 3
        private const val INITIAL_ANNOUNCE_BURST_INTERVAL_MS = 1_000L
        private const val PERIODIC_ANNOUNCE_INTERVAL_MS = 30_000L
        private const val NETWORK_RESELECT_DEBOUNCE_MS = 500L

        private const val DNS_FLAGS_RESPONSE_AUTHORITATIVE = 0x8400
        private const val DNS_CLASS_IN = 0x0001
        private const val DNS_CLASS_CACHE_FLUSH = 0x8000
        private const val DNS_TYPE_A = 1
        private const val DNS_TYPE_PTR = 12
        private const val DNS_TYPE_TXT = 16
        private const val DNS_TYPE_SRV = 33
    }

    private data class SelectedEndpoint(
        val network: Network,
        val ipv4Address: Inet4Address,
        val transports: Set<NsdAdvertiseTransport>,
        val transportLabel: String,
    )

    private data class AdvertisementRegistration(
        val settings: ProxySettings,
        val macAddress: String,
        val port: Int,
    )

    private data class DnsRecord(
        val name: String,
        val type: Int,
        val klass: Int,
        val ttl: Int,
        val rdata: ByteArray,
    )

}
