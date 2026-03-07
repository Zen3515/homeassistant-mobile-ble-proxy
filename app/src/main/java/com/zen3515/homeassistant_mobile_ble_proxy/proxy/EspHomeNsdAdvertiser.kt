package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
    private var activeSocket: MulticastSocket? = null

    @Synchronized
    fun register(settings: ProxySettings, macAddress: String, port: Int) {
        unregister()
        if (settings.nsdInterfaceMode == NsdInterfaceMode.DISABLED) {
            onLog("mDNS advertisement disabled by settings")
            return
        }

        val endpoint = selectEndpoint(settings.nsdInterfaceMode) ?: return
        val serviceName = ProxyIdentity.sanitizeServiceName(settings.nodeName)
        val hostName = "$serviceName.local"
        val instanceName = "$serviceName.$ESPHOME_SERVICE_TYPE"
        val txtAttributes = linkedMapOf(
            "version" to "2026.3.0",
            "mac" to macAddress,
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
            port = port,
            address = endpoint.ipv4Address,
            txtAttributes = txtAttributes,
            ttlSeconds = RECORD_TTL_SECONDS,
        )
        val goodbyePacket = buildAnnouncementPacket(
            instanceName = instanceName,
            hostName = hostName,
            port = port,
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
                "(network=${endpoint.network}, ip=${endpoint.ipv4Address.hostAddress}, transport=${endpoint.transportLabel})",
        )
        onLog(
            "mDNS registration attempt " +
                "(service=$serviceName, type=$ESPHOME_SERVICE_TYPE, port=$port)",
        )
        startAnnouncer(
            endpoint = endpoint,
            announcePacket = announcePacket,
            goodbyePacket = goodbyePacket,
            multicastAddress = multicastAddress,
            instanceName = instanceName,
            servicePort = port,
        )
    }

    @Synchronized
    fun unregister() {
        val job = announcerJob
        announcerJob = null
        job?.cancel()
        runCatching {
            activeSocket?.close()
        }.onFailure { error ->
            onError("Unable to close mDNS socket: ${error.message}")
        }
        activeSocket = null
    }

    fun shutdown() {
        unregister()
        scope.cancel()
    }

    private fun startAnnouncer(
        endpoint: SelectedEndpoint,
        announcePacket: ByteArray,
        goodbyePacket: ByteArray,
        multicastAddress: InetAddress,
        instanceName: String,
        servicePort: Int,
    ) {
        announcerJob = scope.launch {
            val socket = createSocket(endpoint.network) ?: return@launch
            activeSocket = socket
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
                if (activeSocket === socket) {
                    activeSocket = null
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

    private fun selectEndpoint(mode: NsdInterfaceMode): SelectedEndpoint? {
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
            if (!matchesMode(mode, capabilities)) {
                return@mapNotNull null
            }
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
        return endpoints.firstOrNull { endpoint -> endpoint.network == activeNetwork } ?: endpoints.first()
    }

    private fun getAllNetworksCompat(manager: ConnectivityManager): List<Network> {
        val method = ConnectivityManager::class.java.getMethod("getAllNetworks")
        val networks = method.invoke(manager) as? Array<*> ?: return emptyList()
        return networks.filterIsInstance<Network>()
    }

    private fun matchesMode(mode: NsdInterfaceMode, capabilities: NetworkCapabilities): Boolean {
        return when (mode) {
            NsdInterfaceMode.AUTO -> true
            NsdInterfaceMode.WIFI -> capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            NsdInterfaceMode.CELLULAR -> capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            NsdInterfaceMode.VPN -> capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            NsdInterfaceMode.DISABLED -> false
        }
    }

    private fun transportLabel(capabilities: NetworkCapabilities): String {
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
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
        val transportLabel: String,
    )

    private data class DnsRecord(
        val name: String,
        val type: Int,
        val klass: Int,
        val ttl: Int,
        val rdata: ByteArray,
    )

}
