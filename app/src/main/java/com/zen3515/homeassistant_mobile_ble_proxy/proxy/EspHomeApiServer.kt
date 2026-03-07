package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class EspHomeApiServer(
    private val context: Context,
    private val settings: ProxySettings,
    private val macAddress: String,
    private val scannerEngine: BluetoothScanEngine,
    private val onAdvertisementMatchedFilters: (RawAdvertisement) -> Unit = {},
    private val onError: (String) -> Unit,
    private val onLog: (String) -> Unit = {},
) {
    private val noisePsk = decodeNoisePsk(settings.espHomeApiEncryptionKey)
    private val compiledAdvertisementFilters = compileAdvertisementFilters(settings.advertisementFilters)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var acceptJob: Job? = null
    private var advertisementFlushJob: Job? = null
    private var serverSocket: ServerSocket? = null

    private val clientsLock = Any()
    private val clients = mutableSetOf<ClientSession>()
    private val pendingAdvertisementsLock = Any()
    private val pendingAdvertisements = java.util.LinkedHashMap<Long, RawAdvertisement>()
    private val pendingFingerprintToAddress = HashMap<AdvertisementFingerprint, Long>()
    private val pendingAddressToFingerprint = HashMap<Long, AdvertisementFingerprint>()
    private val lastObservedAtByFingerprint = java.util.LinkedHashMap<AdvertisementFingerprint, Long>()
    private val dedupLock = Any()
    private val lastForwardedAdvertisementStateByAddress = HashMap<Long, DedupState>()
    private val discoveryThrottleLock = Any()
    private val discoveryThrottleStateByAddress = HashMap<Long, DiscoveryThrottleState>()
    private val immediateFlushScheduled = AtomicBoolean(false)

    private val gattManager = BluetoothGattProxyManager(
        context = context,
        maxConnections = MAX_CONNECTION_SLOTS,
        onEvent = { event ->
            scope.launch {
                handleGattEvent(event)
            }
        },
        onError = onError,
    )

    @Volatile
    private var configuredScannerMode: ScannerMode = settings.scannerMode

    @Volatile
    private var scannerState: RuntimeScannerState = RuntimeScannerState.IDLE
    @Volatile
    private var forwardedAdvertisements: Long = 0
    @Volatile
    private var receivedAdvertisements: Long = 0
    @Volatile
    private var droppedAdvertisements: Long = 0
    @Volatile
    private var deduplicatedAdvertisements: Long = 0
    @Volatile
    private var filteredAdvertisements: Long = 0
    @Volatile
    private var discoveryThrottledAdvertisements: Long = 0
    @Volatile
    private var immediateFlushTriggers: Long = 0
    @Volatile
    private var lastAdvertisementReceivedAtMs: Long = 0
    @Volatile
    private var lastScannerRunningAtMs: Long = 0
    @Volatile
    private var lastScannerRecoveryAtMs: Long = 0
    @Volatile
    private var lastReceiveRateSampleAtMs: Long = 0
    @Volatile
    private var lastReceiveRateSampleCount: Long = 0
    @Volatile
    private var peakReceiveRatePerSecond: Double = 0.0
    @Volatile
    private var lowRateConsecutiveChecks: Int = 0

    fun start(): Int {
        if (serverSocket != null) {
            return serverSocket?.localPort ?: settings.apiPort
        }
        resetAdvertisementRuntimeState()

        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("0.0.0.0", settings.apiPort))
        serverSocket = socket

        acceptJob = scope.launch {
            acceptLoop(socket)
        }
        advertisementFlushJob = scope.launch {
            val flushIntervalMs = settings.advertisementFlushIntervalMs.toLong()
            while (isActive) {
                delay(flushIntervalMs)
                flushPendingAdvertisements()
            }
        }

        val filterSummary = if (compiledAdvertisementFilters.isEmpty()) {
            "allow-all"
        } else {
            "${compiledAdvertisementFilters.size} rule(s)"
        }
        log(
            "API server started (port=${socket.localPort}, encryption=${if (noisePsk != null) "on" else "off"}, filters=$filterSummary, discovery_throttle=${settings.advertisementDiscoveryThrottleIntervalMs}ms, rediscovery=${DISCOVERY_REDISCOVERY_WINDOW_MS}ms, low_rate_checks=${settings.scannerLowRateConsecutiveChecks})",
        )
        return socket.localPort
    }

    fun stop() {
        log("API server stopping")
        runCatching {
            acceptJob?.cancel()
            advertisementFlushJob?.cancel()
            serverSocket?.close()
        }

        serverSocket = null
        acceptJob = null
        advertisementFlushJob = null
        resetAdvertisementRuntimeState()

        gattManager.stop()

        val sessions = synchronized(clientsLock) {
            val snapshot = clients.toList()
            clients.clear()
            snapshot
        }

        sessions.forEach { it.closeQuietly() }
        ProxyRuntimeState.setClientCount(0)
        log("API server stopped")
    }

    fun publishAdvertisement(advertisement: RawAdvertisement) {
        lastAdvertisementReceivedAtMs = SystemClock.elapsedRealtime()
        receivedAdvertisements += 1
        if (receivedAdvertisements == 1L || receivedAdvertisements % 1000L == 0L) {
            val stats = pendingQueueStats()
            log(
                "Received BLE advertisements: $receivedAdvertisements " +
                    "(pending=${stats.pendingSize}/${stats.pendingCapacity} ${stats.pendingUtilizationPercent}%, " +
                    "observed_fingerprints=${stats.observedFingerprintCount})",
            )
        }

        if (!matchesConfiguredAdvertisementFilters(advertisement)) {
            filteredAdvertisements += 1
            if (filteredAdvertisements == 1L || filteredAdvertisements % 500L == 0L) {
                val stats = pendingQueueStats()
                log(
                    "Filtered BLE advertisements by rules: $filteredAdvertisements " +
                        "(pending=${stats.pendingSize}/${stats.pendingCapacity} ${stats.pendingUtilizationPercent}%, " +
                        "observed_fingerprints=${stats.observedFingerprintCount})",
                )
            }
            return
        }
        if (compiledAdvertisementFilters.isNotEmpty()) {
            onAdvertisementMatchedFilters(advertisement)
        }

        var shouldLogDropCount = false
        val now = SystemClock.elapsedRealtime()
        val (currentDropCount, shouldFlushImmediately) = synchronized(pendingAdvertisementsLock) {
            val fingerprint = advertisement.fingerprint()
            val lastObservedAt = lastObservedAtByFingerprint.remove(fingerprint)
            val unseenForRediscoveryWindow = lastObservedAt == null || now - lastObservedAt >= DISCOVERY_REDISCOVERY_WINDOW_MS
            lastObservedAtByFingerprint[fingerprint] = now
            while (lastObservedAtByFingerprint.size > MAX_DISCOVERY_OBSERVATION_ENTRIES) {
                val oldestObservedKey = lastObservedAtByFingerprint.entries.firstOrNull()?.key ?: break
                lastObservedAtByFingerprint.remove(oldestObservedKey)
            }

            val dropped = enqueuePendingAdvertisementLocked(advertisement, fingerprint)
            if (dropped) {
                droppedAdvertisements += 1
                shouldLogDropCount = droppedAdvertisements == 1L || droppedAdvertisements % 500L == 0L
            }

            droppedAdvertisements to unseenForRediscoveryWindow
        }
        if (shouldLogDropCount) {
            val stats = pendingQueueStats()
            log(
                "Dropped BLE advertisements due to backlog: $currentDropCount " +
                    "(pending=${stats.pendingSize}/${stats.pendingCapacity} ${stats.pendingUtilizationPercent}%, " +
                    "observed_fingerprints=${stats.observedFingerprintCount})",
            )
        }

        if (shouldFlushImmediately && immediateFlushScheduled.compareAndSet(false, true)) {
            immediateFlushTriggers += 1
            if (immediateFlushTriggers == 1L || immediateFlushTriggers % 100L == 0L) {
                log("Immediate flush triggered for new/rediscovered address: $immediateFlushTriggers")
            }
            scope.launch {
                try {
                    flushPendingAdvertisements()
                } finally {
                    immediateFlushScheduled.set(false)
                }
            }
        }
    }

    fun recoverScannerIfStalled(maxSilenceMs: Long = DEFAULT_SCANNER_STALL_TIMEOUT_MS): Boolean {
        if (scannerState != RuntimeScannerState.RUNNING) {
            resetReceiveRateTracking()
            return false
        }

        val hasAdvertisementSubscribers = synchronized(clientsLock) { clients.any { it.subscribedAdvertisements } }
        if (!hasAdvertisementSubscribers) {
            resetReceiveRateTracking()
            return false
        }

        val now = SystemClock.elapsedRealtime()
        val lastReceivedAt = lastAdvertisementReceivedAtMs
        if (lastReceivedAt <= 0L) {
            val scannerRunningSince = lastScannerRunningAtMs
            if (scannerRunningSince <= 0L) {
                return false
            }

            val noResultDurationMs = now - scannerRunningSince
            if (noResultDurationMs >= maxSilenceMs) {
                val reason = "No BLE advertisements observed for ${noResultDurationMs}ms after scanner entered running state"
                return restartScannerForRecovery(reason, now)
            }
            return false
        }

        val silenceMs = now - lastReceivedAt
        if (silenceMs >= maxSilenceMs) {
            val reason = "No BLE advertisements for ${silenceMs}ms while clients are subscribed"
            return restartScannerForRecovery(reason, now)
        }

        val lowRateReason = evaluateLowRateScannerHealth(now)
        if (lowRateReason != null) {
            return restartScannerForRecovery(lowRateReason, now)
        }

        return false
    }

    fun onScannerStateChanged(state: RuntimeScannerState) {
        scannerState = state
        if (state == RuntimeScannerState.RUNNING) {
            lastScannerRunningAtMs = SystemClock.elapsedRealtime()
        } else if (state == RuntimeScannerState.IDLE ||
            state == RuntimeScannerState.STOPPED ||
            state == RuntimeScannerState.FAILED
        ) {
            lastScannerRunningAtMs = 0L
        }
        scope.launch {
            broadcastScannerState()
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (scope.isActive) {
            val clientSocket = runCatching { socket.accept() }
                .getOrElse {
                    if (scope.isActive) {
                        onError("Server accept loop failed: ${it.message}")
                    }
                    return
                }

            scope.launch {
                handleClient(clientSocket)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.tcpNoDelay = true
        val remoteAddress = socket.inetAddress?.hostAddress ?: socket.remoteSocketAddress.toString()

        val session = runCatching {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val transport: EspHomeTransport = if (noisePsk != null) {
                EspHomeNoiseTransport(
                    input = input,
                    output = output,
                    nodeName = settings.nodeName,
                    macAddress = macAddress,
                    preSharedKey = noisePsk,
                )
            } else {
                EspHomePlaintextTransport(input, output)
            }

            ClientSession(
                socket = socket,
                transport = transport,
                remoteAddress = remoteAddress,
            )
        }.getOrElse { error ->
            if (error is EspHomeNoiseTransport.PlaintextProbeException) {
                log("Rejected plaintext probe from $remoteAddress on encrypted API")
                // Hint plaintext clients that this endpoint requires Noise encryption.
                runCatching {
                    socket.getOutputStream().write(REQUIRES_ENCRYPTION_HINT_FRAME)
                    socket.getOutputStream().flush()
                }
            }
            runCatching { socket.close() }
            if (error !is IOException) {
                onError("Client session setup failed: ${error.message}")
            }
            return
        }

        log("Client connected: $remoteAddress")
        registerClient(session)

        var lastInboundType: Int? = null
        try {
            while (scope.isActive) {
                val frame = session.transport.readFrame()
                if (frame == null) {
                    log(
                        "Client $remoteAddress closed stream (last=${messageTypeName(lastInboundType)})",
                    )
                    break
                }
                lastInboundType = frame.typeId
                val keepOpen = handleFrame(session, frame)
                if (!keepOpen) {
                    log(
                        "Closing client $remoteAddress after ${messageTypeName(frame.typeId)}",
                    )
                    break
                }
            }
        } catch (e: IOException) {
            log(
                "Client $remoteAddress IO disconnect: ${e.message ?: e.javaClass.simpleName} " +
                    "(last=${messageTypeName(lastInboundType)})",
            )
        } catch (e: Exception) {
            onError(
                "Client session failed (${messageTypeName(lastInboundType)}): ${e.message}",
            )
        } finally {
            unregisterClient(session)
            session.closeQuietly()
            log("Client disconnected: $remoteAddress")
        }
    }

    private fun registerClient(session: ClientSession) {
        val count = synchronized(clientsLock) {
            clients.add(session)
            clients.size
        }
        ProxyRuntimeState.setClientCount(count)
        log("Clients now: $count")
    }

    private fun unregisterClient(session: ClientSession) {
        var shouldStopScanner = false
        val count = synchronized(clientsLock) {
            val removed = clients.remove(session)
            if (removed && session.subscribedAdvertisements && clients.none { it.subscribedAdvertisements }) {
                shouldStopScanner = true
            }
            clients.size
        }
        ProxyRuntimeState.setClientCount(count)

        if (shouldStopScanner) {
            scannerEngine.stop()
            log("Stopped scanner because no subscribed BLE advertisement clients remain")
        }
        log("Clients now: $count")
    }

    private fun handleFrame(session: ClientSession, frame: EspHomeFrameCodec.Frame): Boolean {
        return when (frame.typeId) {
            EspHomeMessageType.HELLO_REQUEST -> {
                if (!EspHomeProtoCodec.canParse(frame.payload)) {
                    return false
                }

                session.send(
                    EspHomeMessageType.HELLO_RESPONSE,
                    EspHomeProtoCodec.encodeHelloResponse(
                        apiMajor = 1,
                        apiMinor = 14,
                        serverInfo = "Android BLE Proxy",
                        name = settings.nodeName,
                    ),
                )
                true
            }

            EspHomeMessageType.PING_REQUEST -> {
                session.send(EspHomeMessageType.PING_RESPONSE, EMPTY_PAYLOAD)
                true
            }

            EspHomeMessageType.DISCONNECT_REQUEST -> {
                session.send(EspHomeMessageType.DISCONNECT_RESPONSE, EMPTY_PAYLOAD)
                false
            }

            EspHomeMessageType.DEVICE_INFO_REQUEST -> {
                if (!EspHomeProtoCodec.canParse(frame.payload)) {
                    return false
                }
                session.send(EspHomeMessageType.DEVICE_INFO_RESPONSE, buildDeviceInfoResponse())
                true
            }

            EspHomeMessageType.LIST_ENTITIES_REQUEST -> {
                if (!EspHomeProtoCodec.canParse(frame.payload)) {
                    return false
                }
                session.send(EspHomeMessageType.LIST_ENTITIES_DONE_RESPONSE, EMPTY_PAYLOAD)
                true
            }

            EspHomeMessageType.SUBSCRIBE_STATES_REQUEST -> EspHomeProtoCodec.canParse(frame.payload)

            34, // SubscribeHomeassistantServicesRequest
            38, // SubscribeHomeAssistantStatesRequest
            -> EspHomeProtoCodec.canParse(frame.payload)

            EspHomeMessageType.SUBSCRIBE_BLUETOOTH_LE_ADVERTISEMENTS_REQUEST -> {
                if (!EspHomeProtoCodec.canParse(frame.payload)) {
                    return false
                }

                session.subscribedAdvertisements = true
                log("Client ${session.remoteAddress} subscribed BLE advertisements")
                updateScannerSubscriptionState()
                sendConnectionsFree(session)
                sendScannerState(session)
                true
            }

            EspHomeMessageType.UNSUBSCRIBE_BLUETOOTH_LE_ADVERTISEMENTS_REQUEST -> {
                if (!EspHomeProtoCodec.canParse(frame.payload)) {
                    return false
                }

                session.subscribedAdvertisements = false
                log("Client ${session.remoteAddress} unsubscribed BLE advertisements")
                updateScannerSubscriptionState()
                sendScannerState(session)
                true
            }

            EspHomeMessageType.SUBSCRIBE_BLUETOOTH_CONNECTIONS_FREE_REQUEST -> {
                if (!EspHomeProtoCodec.canParse(frame.payload)) {
                    return false
                }

                session.subscribedConnectionsFree = true
                sendConnectionsFree(session)
                true
            }

            EspHomeMessageType.BLUETOOTH_SCANNER_SET_MODE_REQUEST -> {
                val mode = EspHomeProtoCodec.parseScannerMode(frame.payload) ?: return false
                configuredScannerMode = mode
                log("Scanner mode set by client ${session.remoteAddress}: ${mode.name.lowercase()}")
                scannerEngine.setMode(configuredScannerMode)
                broadcastScannerState()
                true
            }

            EspHomeMessageType.BLUETOOTH_DEVICE_REQUEST -> {
                val request = EspHomeProtoCodec.parseDeviceRequest(frame.payload) ?: return false
                gattManager.handleDeviceRequest(request)
                true
            }

            EspHomeMessageType.BLUETOOTH_GATT_GET_SERVICES_REQUEST -> {
                val address = EspHomeProtoCodec.parseAddress(frame.payload) ?: return false
                gattManager.handleGetServices(address)
                true
            }

            EspHomeMessageType.BLUETOOTH_GATT_READ_REQUEST -> {
                val request = EspHomeProtoCodec.parseAddressHandle(frame.payload) ?: return false
                gattManager.handleRead(request)
                true
            }

            EspHomeMessageType.BLUETOOTH_GATT_WRITE_REQUEST -> {
                val request = EspHomeProtoCodec.parseGattWriteRequest(frame.payload) ?: return false
                gattManager.handleWrite(request)
                true
            }

            EspHomeMessageType.BLUETOOTH_GATT_READ_DESCRIPTOR_REQUEST -> {
                val request = EspHomeProtoCodec.parseAddressHandle(frame.payload) ?: return false
                gattManager.handleReadDescriptor(request)
                true
            }

            EspHomeMessageType.BLUETOOTH_GATT_WRITE_DESCRIPTOR_REQUEST -> {
                val request = EspHomeProtoCodec.parseGattWriteDescriptorRequest(frame.payload) ?: return false
                gattManager.handleWriteDescriptor(request)
                true
            }

            EspHomeMessageType.BLUETOOTH_GATT_NOTIFY_REQUEST -> {
                val request = EspHomeProtoCodec.parseGattNotifyRequest(frame.payload) ?: return false
                gattManager.handleNotify(request)
                true
            }

            else -> true
        }
    }

    private fun handleGattEvent(event: BluetoothGattProxyManager.Event) {
        when (event) {
            is BluetoothGattProxyManager.Event.DeviceConnection -> {
                broadcast(
                    typeId = EspHomeMessageType.BLUETOOTH_DEVICE_CONNECTION_RESPONSE,
                    payload = EspHomeProtoCodec.encodeDeviceConnectionResponse(
                        address = event.address,
                        connected = event.connected,
                        mtu = event.mtu,
                        error = event.error,
                    ),
                ) { true }
            }

            is BluetoothGattProxyManager.Event.DevicePairing -> {
                broadcast(
                    typeId = EspHomeMessageType.BLUETOOTH_DEVICE_PAIRING_RESPONSE,
                    payload = EspHomeProtoCodec.encodeDevicePairingResponse(
                        address = event.address,
                        paired = event.paired,
                        error = event.error,
                    ),
                ) { true }
            }

            is BluetoothGattProxyManager.Event.DeviceUnpairing -> {
                broadcast(
                    typeId = EspHomeMessageType.BLUETOOTH_DEVICE_UNPAIRING_RESPONSE,
                    payload = EspHomeProtoCodec.encodeDeviceUnpairingResponse(
                        address = event.address,
                        success = event.success,
                        error = event.error,
                    ),
                ) { true }
            }

            is BluetoothGattProxyManager.Event.DeviceClearCache -> {
                broadcast(
                    typeId = EspHomeMessageType.BLUETOOTH_DEVICE_CLEAR_CACHE_RESPONSE,
                    payload = EspHomeProtoCodec.encodeDeviceClearCacheResponse(
                        address = event.address,
                        success = event.success,
                        error = event.error,
                    ),
                ) { true }
            }

            is BluetoothGattProxyManager.Event.ConnectionsChanged -> {
                broadcastConnectionsFree()
            }

            is BluetoothGattProxyManager.Event.GattServices -> {
                broadcast(
                    typeId = EspHomeMessageType.BLUETOOTH_GATT_GET_SERVICES_RESPONSE,
                    payload = EspHomeProtoCodec.encodeGattGetServicesResponse(
                        address = event.address,
                        services = event.services,
                    ),
                ) { true }
            }

            is BluetoothGattProxyManager.Event.GattServicesDone -> {
                broadcast(
                    typeId = EspHomeMessageType.BLUETOOTH_GATT_GET_SERVICES_DONE_RESPONSE,
                    payload = EspHomeProtoCodec.encodeGattGetServicesDoneResponse(event.address),
                ) { true }
            }

            is BluetoothGattProxyManager.Event.GattRead -> {
                broadcast(
                    typeId = EspHomeMessageType.BLUETOOTH_GATT_READ_RESPONSE,
                    payload = EspHomeProtoCodec.encodeGattReadResponse(
                        address = event.address,
                        handle = event.handle,
                        data = event.data,
                    ),
                ) { true }
            }

            is BluetoothGattProxyManager.Event.GattWrite -> {
                broadcast(
                    typeId = EspHomeMessageType.BLUETOOTH_GATT_WRITE_RESPONSE,
                    payload = EspHomeProtoCodec.encodeGattWriteResponse(
                        address = event.address,
                        handle = event.handle,
                    ),
                ) { true }
            }

            is BluetoothGattProxyManager.Event.GattNotify -> {
                broadcast(
                    typeId = EspHomeMessageType.BLUETOOTH_GATT_NOTIFY_RESPONSE,
                    payload = EspHomeProtoCodec.encodeGattNotifyResponse(
                        address = event.address,
                        handle = event.handle,
                    ),
                ) { true }
            }

            is BluetoothGattProxyManager.Event.GattNotifyData -> {
                broadcast(
                    typeId = EspHomeMessageType.BLUETOOTH_GATT_NOTIFY_DATA_RESPONSE,
                    payload = EspHomeProtoCodec.encodeGattNotifyDataResponse(
                        address = event.address,
                        handle = event.handle,
                        data = event.data,
                    ),
                ) { true }
            }

            is BluetoothGattProxyManager.Event.GattError -> {
                broadcast(
                    typeId = EspHomeMessageType.BLUETOOTH_GATT_ERROR_RESPONSE,
                    payload = EspHomeProtoCodec.encodeGattErrorResponse(
                        address = event.address,
                        handle = event.handle,
                        error = event.error,
                    ),
                ) { true }
            }
        }
    }

    private fun updateScannerSubscriptionState() {
        val (subscriberCount, shouldRun) = synchronized(clientsLock) {
            val count = clients.count { it.subscribedAdvertisements }
            count to (count > 0)
        }

        if (shouldRun) {
            log("BLE advertisement subscribers=$subscriberCount, starting scanner (${configuredScannerMode.name.lowercase()})")
            scannerEngine.start(configuredScannerMode)
        } else {
            log("BLE advertisement subscribers=0, stopping scanner")
            scannerEngine.stop()
        }
    }

    private fun sendConnectionsFree(session: ClientSession) {
        val allocated = gattManager.allocatedConnections()
        val limit = gattManager.connectionLimit()
        val payload = EspHomeProtoCodec.encodeConnectionsFreeResponse(
            free = (limit - allocated.size).coerceAtLeast(0),
            limit = limit,
            allocated = allocated,
        )
        session.send(EspHomeMessageType.BLUETOOTH_CONNECTIONS_FREE_RESPONSE, payload)
    }

    private fun broadcastConnectionsFree() {
        val allocated = gattManager.allocatedConnections()
        val limit = gattManager.connectionLimit()
        val payload = EspHomeProtoCodec.encodeConnectionsFreeResponse(
            free = (limit - allocated.size).coerceAtLeast(0),
            limit = limit,
            allocated = allocated,
        )
        broadcast(
            typeId = EspHomeMessageType.BLUETOOTH_CONNECTIONS_FREE_RESPONSE,
            payload = payload,
        ) { it.subscribedConnectionsFree }
    }

    private fun broadcastScannerState() {
        val payload = buildScannerStateResponse()
        broadcast(EspHomeMessageType.BLUETOOTH_SCANNER_STATE_RESPONSE, payload) { it.subscribedAdvertisements }
    }

    private fun sendScannerState(session: ClientSession) {
        session.send(EspHomeMessageType.BLUETOOTH_SCANNER_STATE_RESPONSE, buildScannerStateResponse())
    }

    private fun buildScannerStateResponse(): ByteArray {
        return EspHomeProtoCodec.encodeScannerStateResponse(
            scannerState = scannerState,
            mode = configuredScannerMode,
        )
    }

    private fun buildDeviceInfoResponse(): ByteArray {
        val buildManufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "Android" }
        return EspHomeProtoCodec.encodeDeviceInfoResponse(
            nodeName = settings.nodeName,
            macAddress = macAddress,
            esphomeVersion = "2026.3.0-android",
            compilationTime = "android-runtime",
            model = "Android",
            legacyBluetoothProxyVersion = 5,
            manufacturer = buildManufacturer,
            friendlyName = settings.friendlyName,
            bluetoothProxyFeatureFlags = BluetoothProxyFeatureFlags.ACTIVE_FEATURE_FLAGS,
            bluetoothMacAddress = macAddress,
            apiEncryptionSupported = true,
        )
    }

    private fun flushPendingAdvertisements() {
        val batch = synchronized(pendingAdvertisementsLock) {
            drainPendingAdvertisementsLocked(ADVERTISEMENT_BATCH_SIZE)
        }
        sendAdvertisementBatch(batch)
    }

    private fun sendAdvertisementBatch(batch: List<RawAdvertisement>) {
        val dedupFiltered = applyAdvertisementDedup(batch)
        val filtered = applyDiscoveryThrottle(dedupFiltered)
        if (filtered.isEmpty()) {
            return
        }

        val payload = EspHomeProtoCodec.encodeRawAdvertisementsResponse(filtered)
        broadcast(
            typeId = EspHomeMessageType.BLUETOOTH_LE_RAW_ADVERTISEMENTS_RESPONSE,
            payload = payload,
        ) { it.subscribedAdvertisements }

        for (advertisement in filtered) {
            log(
                "Forwarded BLE advertisement: addr=${ProxyIdentity.longToMac(advertisement.address)}, " +
                    "rssi=${advertisement.rssi}, bytes=${advertisement.data.size}",
            )
        }

        ProxyRuntimeState.incrementAdvertisementsForwarded(filtered.size)
        forwardedAdvertisements += filtered.size.toLong()
        if (forwardedAdvertisements == 1L || forwardedAdvertisements % 500L == 0L) {
            log("Forwarded BLE advertisements: $forwardedAdvertisements")
        }
    }

    private fun applyDiscoveryThrottle(batch: List<RawAdvertisement>): List<RawAdvertisement> {
        val throttleIntervalMs = settings.advertisementDiscoveryThrottleIntervalMs.toLong()
        if (throttleIntervalMs <= 0L) {
            return batch
        }

        val now = SystemClock.elapsedRealtime()
        val activeInteractionAddresses = gattManager.allocatedConnections().toHashSet()
        var shouldLogThrottleCount = false
        val forwarded = ArrayList<RawAdvertisement>(batch.size)

        synchronized(discoveryThrottleLock) {
            for (advertisement in batch) {
                if (advertisement.address in activeInteractionAddresses) {
                    forwarded += advertisement
                    continue
                }

                val state = discoveryThrottleStateByAddress[advertisement.address]
                if (state == null) {
                    discoveryThrottleStateByAddress[advertisement.address] = DiscoveryThrottleState(
                        lastSeenAtMs = now,
                        lastForwardedAtMs = now,
                        pendingAdvertisement = null,
                    )
                    forwarded += advertisement
                    continue
                }

                val rediscovered = now - state.lastSeenAtMs >= DISCOVERY_REDISCOVERY_WINDOW_MS
                state.lastSeenAtMs = now
                if (rediscovered || now - state.lastForwardedAtMs >= throttleIntervalMs) {
                    state.lastForwardedAtMs = now
                    state.pendingAdvertisement = null
                    forwarded += advertisement
                } else {
                    state.pendingAdvertisement = advertisement
                    discoveryThrottledAdvertisements += 1
                    shouldLogThrottleCount = discoveryThrottledAdvertisements == 1L ||
                        discoveryThrottledAdvertisements % 500L == 0L
                }
            }

            // Flush latest pending per address whenever the interval elapses,
            // even if no new packet for that address arrives in this cycle.
            val iterator = discoveryThrottleStateByAddress.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val state = entry.value
                if (entry.key in activeInteractionAddresses) {
                    continue
                }
                val pending = state.pendingAdvertisement
                if (pending != null && now - state.lastForwardedAtMs >= throttleIntervalMs) {
                    state.pendingAdvertisement = null
                    state.lastForwardedAtMs = now
                    forwarded += pending
                }
            }

            pruneDiscoveryThrottleStateLocked(now, throttleIntervalMs)
        }

        if (shouldLogThrottleCount) {
            log("Discovery-throttled BLE advertisements: $discoveryThrottledAdvertisements")
        }

        return forwarded
    }

    private fun applyAdvertisementDedup(batch: List<RawAdvertisement>): List<RawAdvertisement> {
        val dedupWindowMs = settings.advertisementDedupWindowMs.toLong()
        val activeInteractionAddresses = gattManager.allocatedConnections().toHashSet()
        if (dedupWindowMs <= 0L) {
            return batch
        }

        val now = SystemClock.elapsedRealtime()
        var shouldLogDedupCount = false
        val filtered = ArrayList<RawAdvertisement>(batch.size)

        synchronized(dedupLock) {
            for (advertisement in batch) {
                // Active GATT interaction addresses bypass discovery dedup to keep
                // live BLE operations responsive.
                if (advertisement.address in activeInteractionAddresses) {
                    filtered += advertisement
                    continue
                }

                val payloadHash = advertisement.data.contentHashCode()
                val last = lastForwardedAdvertisementStateByAddress[advertisement.address]
                val unchangedPayload = last != null &&
                    last.payloadHash == payloadHash &&
                    last.addressType == advertisement.addressType &&
                    last.name == advertisement.name
                if (unchangedPayload && now - last.forwardedAtMs < dedupWindowMs) {
                    deduplicatedAdvertisements += 1
                    shouldLogDedupCount = deduplicatedAdvertisements == 1L || deduplicatedAdvertisements % 500L == 0L
                    continue
                }

                lastForwardedAdvertisementStateByAddress[advertisement.address] = DedupState(
                    forwardedAtMs = now,
                    payloadHash = payloadHash,
                    addressType = advertisement.addressType,
                    name = advertisement.name,
                )
                filtered += advertisement
            }

            pruneDedupMapLocked(now, dedupWindowMs)
        }

        if (shouldLogDedupCount) {
            log("Deduplicated BLE advertisements: $deduplicatedAdvertisements")
        }

        return filtered
    }

    private fun pruneDiscoveryThrottleStateLocked(now: Long, throttleIntervalMs: Long) {
        val expirationAgeMs = maxOf(
            DISCOVERY_REDISCOVERY_WINDOW_MS * 2L,
            throttleIntervalMs * 3L,
        )
        val iterator = discoveryThrottleStateByAddress.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val state = entry.value
            if (state.pendingAdvertisement == null && now - state.lastSeenAtMs > expirationAgeMs) {
                iterator.remove()
            }
        }

        if (discoveryThrottleStateByAddress.size <= MAX_DISCOVERY_THROTTLE_ENTRIES) {
            return
        }

        val overflow = discoveryThrottleStateByAddress.size - MAX_DISCOVERY_THROTTLE_ENTRIES
        val trimIterator = discoveryThrottleStateByAddress.entries.iterator()
        repeat(overflow) {
            if (trimIterator.hasNext()) {
                trimIterator.next()
                trimIterator.remove()
            }
        }
    }

    private fun pruneDedupMapLocked(now: Long, dedupWindowMs: Long) {
        val expirationAgeMs = dedupWindowMs * 3L
        val iterator = lastForwardedAdvertisementStateByAddress.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.forwardedAtMs > expirationAgeMs) {
                iterator.remove()
            }
        }

        if (lastForwardedAdvertisementStateByAddress.size <= MAX_DEDUP_ENTRIES) {
            return
        }

        val overflow = lastForwardedAdvertisementStateByAddress.size - MAX_DEDUP_ENTRIES
        val trimIterator = lastForwardedAdvertisementStateByAddress.entries.iterator()
        repeat(overflow) {
            if (trimIterator.hasNext()) {
                trimIterator.next()
                trimIterator.remove()
            }
        }
    }

    private fun enqueuePendingAdvertisementLocked(
        advertisement: RawAdvertisement,
        fingerprint: AdvertisementFingerprint,
    ): Boolean {
        removePendingAdvertisementByAddressLocked(advertisement.address)

        val existingAddressForFingerprint = pendingFingerprintToAddress[fingerprint]
        if (existingAddressForFingerprint != null) {
            removePendingAdvertisementByAddressLocked(existingAddressForFingerprint)
        }

        var dropped = false
        if (pendingAdvertisements.size >= MAX_PENDING_ADVERTISEMENTS) {
            val oldestAddress = pendingAdvertisements.entries.firstOrNull()?.key
            if (oldestAddress != null) {
                removePendingAdvertisementByAddressLocked(oldestAddress)
                dropped = true
            }
        }

        pendingAdvertisements[advertisement.address] = advertisement
        pendingAddressToFingerprint[advertisement.address] = fingerprint
        pendingFingerprintToAddress[fingerprint] = advertisement.address
        return dropped
    }

    private fun removePendingAdvertisementByAddressLocked(address: Long) {
        val removed = pendingAdvertisements.remove(address) ?: return
        val fingerprint = pendingAddressToFingerprint.remove(address)
        if (fingerprint != null && pendingFingerprintToAddress[fingerprint] == address) {
            pendingFingerprintToAddress.remove(fingerprint)
        } else if (fingerprint == null) {
            val recoveredFingerprint = removed.fingerprint()
            if (pendingFingerprintToAddress[recoveredFingerprint] == address) {
                pendingFingerprintToAddress.remove(recoveredFingerprint)
            }
        }
    }

    private fun drainPendingAdvertisementsLocked(maxCount: Int): List<RawAdvertisement> {
        if (pendingAdvertisements.isEmpty()) {
            return emptyList()
        }
        val count = minOf(maxCount, pendingAdvertisements.size)
        val addressesToDrain = pendingAdvertisements.keys.take(count)
        val drained = ArrayList<RawAdvertisement>(addressesToDrain.size)
        for (address in addressesToDrain) {
            val pending = pendingAdvertisements[address] ?: continue
            drained += pending
            removePendingAdvertisementByAddressLocked(address)
        }
        return drained
    }

    private fun broadcast(typeId: Int, payload: ByteArray, filter: (ClientSession) -> Boolean) {
        val sessions = synchronized(clientsLock) { clients.filter(filter) }
        if (sessions.isEmpty()) {
            return
        }

        for (session in sessions) {
            runCatching {
                session.send(typeId, payload)
            }.onFailure {
                log(
                    "Send failed (${messageTypeName(typeId)}) to ${session.remoteAddress}: " +
                        "${it.javaClass.simpleName}: ${it.message}",
                )
                session.closeQuietly()
                unregisterClient(session)
            }
        }
    }

    private class ClientSession(
        val socket: Socket,
        val transport: EspHomeTransport,
        val remoteAddress: String,
        @Volatile var subscribedAdvertisements: Boolean = false,
        @Volatile var subscribedConnectionsFree: Boolean = false,
    ) : Closeable {
        private val writeLock = Any()

        fun send(typeId: Int, payload: ByteArray) {
            synchronized(writeLock) {
                transport.writeFrame(typeId, payload)
            }
        }

        override fun close() {
            closeQuietly()
        }

        fun closeQuietly() {
            runCatching { socket.close() }
        }
    }

    private fun log(message: String) {
        onLog(message)
    }

    private fun pendingQueueStats(): PendingQueueStats {
        return synchronized(pendingAdvertisementsLock) {
            val pendingSize = pendingAdvertisements.size
            val pendingCapacity = MAX_PENDING_ADVERTISEMENTS
            val utilizationPercent = if (pendingCapacity <= 0) {
                0
            } else {
                (pendingSize * 100) / pendingCapacity
            }
            PendingQueueStats(
                pendingSize = pendingSize,
                pendingCapacity = pendingCapacity,
                pendingUtilizationPercent = utilizationPercent,
                observedFingerprintCount = lastObservedAtByFingerprint.size,
            )
        }
    }

    private fun resetAdvertisementRuntimeState() {
        synchronized(pendingAdvertisementsLock) {
            pendingAdvertisements.clear()
            pendingFingerprintToAddress.clear()
            pendingAddressToFingerprint.clear()
            lastObservedAtByFingerprint.clear()
        }
        synchronized(dedupLock) {
            lastForwardedAdvertisementStateByAddress.clear()
        }
        synchronized(discoveryThrottleLock) {
            discoveryThrottleStateByAddress.clear()
        }
        deduplicatedAdvertisements = 0
        filteredAdvertisements = 0
        discoveryThrottledAdvertisements = 0
        immediateFlushTriggers = 0
        lastAdvertisementReceivedAtMs = 0
        lastScannerRunningAtMs = 0
        lastScannerRecoveryAtMs = 0
        lastReceiveRateSampleAtMs = 0
        lastReceiveRateSampleCount = 0
        peakReceiveRatePerSecond = 0.0
        lowRateConsecutiveChecks = 0
        forwardedAdvertisements = 0
        receivedAdvertisements = 0
        droppedAdvertisements = 0
        immediateFlushScheduled.set(false)
    }

    private fun restartScannerForRecovery(reason: String, nowMs: Long): Boolean {
        if (nowMs - lastScannerRecoveryAtMs < MIN_SCANNER_RECOVERY_INTERVAL_MS) {
            return false
        }
        lastScannerRecoveryAtMs = nowMs

        log("$reason; restarting scanner")
        scannerEngine.stop()
        val started = scannerEngine.start(configuredScannerMode)
        if (!started) {
            log("Scanner recovery restart failed")
            return false
        }

        resetReceiveRateTracking(nowMs)
        return true
    }

    private fun evaluateLowRateScannerHealth(nowMs: Long): String? {
        val previousSampleAtMs = lastReceiveRateSampleAtMs
        if (previousSampleAtMs <= 0L) {
            resetReceiveRateTracking(nowMs)
            return null
        }

        val sampleDurationMs = nowMs - previousSampleAtMs
        val sampleWindowMs = settings.scannerHealthCheckIntervalMs.toLong().coerceAtLeast(MIN_RATE_SAMPLE_WINDOW_MS_MIN)
        if (sampleDurationMs < sampleWindowMs) {
            return null
        }

        val totalReceived = receivedAdvertisements
        val deltaReceived = (totalReceived - lastReceiveRateSampleCount).coerceAtLeast(0L)
        val sampleRatePerSecond = (deltaReceived.toDouble() * 1000.0) / sampleDurationMs.toDouble()

        lastReceiveRateSampleAtMs = nowMs
        lastReceiveRateSampleCount = totalReceived

        val decayedPeak = peakReceiveRatePerSecond * LOW_RATE_PEAK_DECAY_FACTOR
        peakReceiveRatePerSecond = maxOf(decayedPeak, sampleRatePerSecond)

        val baselineRate = peakReceiveRatePerSecond
        if (baselineRate < LOW_RATE_BASELINE_MIN_ADS_PER_SECOND) {
            lowRateConsecutiveChecks = 0
            return null
        }

        val lowRateThreshold = baselineRate * LOW_RATE_RELATIVE_THRESHOLD
        if (sampleRatePerSecond > lowRateThreshold) {
            if (lowRateConsecutiveChecks > 0) {
                log(
                    "Scanner throughput recovered: " +
                        "${formatRate(sampleRatePerSecond)} ads/s " +
                        "(baseline=${formatRate(baselineRate)} ads/s)",
                )
            }
            lowRateConsecutiveChecks = 0
            return null
        }

        lowRateConsecutiveChecks += 1
        val requiredChecks = settings.scannerLowRateConsecutiveChecks.coerceAtLeast(1)
        val lowRateDetails = "Scanner throughput low: " +
            "${formatRate(sampleRatePerSecond)} ads/s " +
            "(baseline=${formatRate(baselineRate)} ads/s, " +
            "threshold=${formatRate(lowRateThreshold)} ads/s)"
        if (lowRateConsecutiveChecks < requiredChecks) {
            if (lowRateConsecutiveChecks == 1) {
                log("$lowRateDetails; waiting before recovery")
            }
            return null
        }

        lowRateConsecutiveChecks = 0
        return "$lowRateDetails sustained for $requiredChecks checks"
    }

    private fun resetReceiveRateTracking(nowMs: Long = SystemClock.elapsedRealtime()) {
        lastReceiveRateSampleAtMs = nowMs
        lastReceiveRateSampleCount = receivedAdvertisements
        peakReceiveRatePerSecond = 0.0
        lowRateConsecutiveChecks = 0
    }

    private fun formatRate(rate: Double): String = String.format(Locale.US, "%.2f", rate)

    private fun compileAdvertisementFilters(
        rules: List<AdvertisementFilterRule>,
    ): List<CompiledAdvertisementFilterRule> {
        if (rules.isEmpty()) {
            return emptyList()
        }

        val compiled = mutableListOf<CompiledAdvertisementFilterRule>()
        for (rule in rules) {
            if (!rule.enabled) {
                continue
            }

            val macRegexString = rule.macRegex.trim()
            val nameRegexString = rule.nameRegex.trim()
            val hasRssiConstraint = rule.minRssi > NO_RSSI_FILTER_CONSTRAINT
            if (macRegexString.isEmpty() && nameRegexString.isEmpty() && !hasRssiConstraint) {
                log("Ignoring advertisement filter ${rule.id}: no MAC/name regex or RSSI threshold")
                continue
            }

            val macRegex = if (macRegexString.isNotEmpty()) {
                runCatching { Regex(macRegexString, RegexOption.IGNORE_CASE) }.getOrNull()
            } else {
                null
            }
            if (macRegexString.isNotEmpty() && macRegex == null) {
                log("Ignoring advertisement filter ${rule.id}: invalid MAC regex ($macRegexString)")
                continue
            }

            val nameRegex = if (nameRegexString.isNotEmpty()) {
                runCatching { Regex(nameRegexString, RegexOption.IGNORE_CASE) }.getOrNull()
            } else {
                null
            }
            if (nameRegexString.isNotEmpty() && nameRegex == null) {
                log("Ignoring advertisement filter ${rule.id}: invalid name regex ($nameRegexString)")
                continue
            }

            compiled += CompiledAdvertisementFilterRule(
                macRegex = macRegex,
                nameRegex = nameRegex,
                minRssi = rule.minRssi.coerceIn(-127, 0),
                hasRssiConstraint = hasRssiConstraint,
            )
        }

        if (compiled.isEmpty() && rules.any { it.enabled }) {
            log("No valid enabled advertisement filters; forwarding all BLE advertisements")
        }

        return compiled
    }

    private fun matchesConfiguredAdvertisementFilters(advertisement: RawAdvertisement): Boolean {
        if (compiledAdvertisementFilters.isEmpty()) {
            return true
        }
        return compiledAdvertisementFilters.any { it.matches(advertisement) }
    }

    companion object {
        private const val MAX_CONNECTION_SLOTS = 5
        private const val ADVERTISEMENT_BATCH_SIZE = 16
        private const val MAX_PENDING_ADVERTISEMENTS = 2_048
        private const val MAX_DISCOVERY_OBSERVATION_ENTRIES = 20_000
        private const val MAX_DEDUP_ENTRIES = 10_000
        private const val MAX_DISCOVERY_THROTTLE_ENTRIES = 10_000
        private const val DISCOVERY_REDISCOVERY_WINDOW_MS = 30L * 60L * 1000L
        private const val DEFAULT_SCANNER_STALL_TIMEOUT_MS = 45_000L
        private const val MIN_SCANNER_RECOVERY_INTERVAL_MS = 30_000L
        private const val MIN_RATE_SAMPLE_WINDOW_MS_MIN = 1_000L
        private const val LOW_RATE_BASELINE_MIN_ADS_PER_SECOND = 5.0
        private const val LOW_RATE_RELATIVE_THRESHOLD = 0.12
        private const val LOW_RATE_PEAK_DECAY_FACTOR = 0.96
        private const val NO_RSSI_FILTER_CONSTRAINT = -127
        private val EMPTY_PAYLOAD = ByteArray(0)
        private val REQUIRES_ENCRYPTION_HINT_FRAME = byteArrayOf(0x01, 0x00, 0x00)

        private fun decodeNoisePsk(encodedPsk: String): ByteArray? {
            if (encodedPsk.isBlank()) {
                return null
            }

            val normalized = encodedPsk.filterNot(Char::isWhitespace)
            val decoded = try {
                Base64.decode(normalized, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "ESPHome API encryption key must be a valid base64 value",
                    e,
                )
            }

            if (decoded.size != 32) {
                throw IllegalArgumentException(
                    "ESPHome API encryption key must decode to 32 bytes (got ${decoded.size})",
                )
            }

            return decoded
        }

        private fun messageTypeName(typeId: Int?): String {
            if (typeId == null) {
                return "none"
            }
            return when (typeId) {
                EspHomeMessageType.HELLO_REQUEST -> "HELLO_REQUEST(1)"
                EspHomeMessageType.HELLO_RESPONSE -> "HELLO_RESPONSE(2)"
                EspHomeMessageType.DISCONNECT_REQUEST -> "DISCONNECT_REQUEST(5)"
                EspHomeMessageType.DISCONNECT_RESPONSE -> "DISCONNECT_RESPONSE(6)"
                EspHomeMessageType.PING_REQUEST -> "PING_REQUEST(7)"
                EspHomeMessageType.PING_RESPONSE -> "PING_RESPONSE(8)"
                EspHomeMessageType.DEVICE_INFO_REQUEST -> "DEVICE_INFO_REQUEST(9)"
                EspHomeMessageType.DEVICE_INFO_RESPONSE -> "DEVICE_INFO_RESPONSE(10)"
                EspHomeMessageType.LIST_ENTITIES_REQUEST -> "LIST_ENTITIES_REQUEST(11)"
                EspHomeMessageType.LIST_ENTITIES_DONE_RESPONSE -> "LIST_ENTITIES_DONE_RESPONSE(19)"
                EspHomeMessageType.SUBSCRIBE_STATES_REQUEST -> "SUBSCRIBE_STATES_REQUEST(20)"
                34 -> "SUBSCRIBE_HOMEASSISTANT_SERVICES_REQUEST(34)"
                38 -> "SUBSCRIBE_HOMEASSISTANT_STATES_REQUEST(38)"
                EspHomeMessageType.SUBSCRIBE_BLUETOOTH_LE_ADVERTISEMENTS_REQUEST -> "SUBSCRIBE_BLE_ADVERTISEMENTS_REQUEST(66)"
                EspHomeMessageType.BLUETOOTH_DEVICE_REQUEST -> "BLUETOOTH_DEVICE_REQUEST(68)"
                EspHomeMessageType.BLUETOOTH_DEVICE_CONNECTION_RESPONSE -> "BLUETOOTH_DEVICE_CONNECTION_RESPONSE(69)"
                EspHomeMessageType.BLUETOOTH_GATT_GET_SERVICES_REQUEST -> "BLUETOOTH_GATT_GET_SERVICES_REQUEST(70)"
                EspHomeMessageType.BLUETOOTH_GATT_GET_SERVICES_RESPONSE -> "BLUETOOTH_GATT_GET_SERVICES_RESPONSE(71)"
                EspHomeMessageType.BLUETOOTH_GATT_GET_SERVICES_DONE_RESPONSE -> "BLUETOOTH_GATT_GET_SERVICES_DONE_RESPONSE(72)"
                EspHomeMessageType.BLUETOOTH_GATT_READ_REQUEST -> "BLUETOOTH_GATT_READ_REQUEST(73)"
                EspHomeMessageType.BLUETOOTH_GATT_READ_RESPONSE -> "BLUETOOTH_GATT_READ_RESPONSE(74)"
                EspHomeMessageType.BLUETOOTH_GATT_WRITE_REQUEST -> "BLUETOOTH_GATT_WRITE_REQUEST(75)"
                EspHomeMessageType.BLUETOOTH_GATT_READ_DESCRIPTOR_REQUEST -> "BLUETOOTH_GATT_READ_DESCRIPTOR_REQUEST(76)"
                EspHomeMessageType.BLUETOOTH_GATT_WRITE_DESCRIPTOR_REQUEST -> "BLUETOOTH_GATT_WRITE_DESCRIPTOR_REQUEST(77)"
                EspHomeMessageType.BLUETOOTH_GATT_NOTIFY_REQUEST -> "BLUETOOTH_GATT_NOTIFY_REQUEST(78)"
                EspHomeMessageType.BLUETOOTH_GATT_NOTIFY_DATA_RESPONSE -> "BLUETOOTH_GATT_NOTIFY_DATA_RESPONSE(79)"
                EspHomeMessageType.SUBSCRIBE_BLUETOOTH_CONNECTIONS_FREE_REQUEST -> "SUBSCRIBE_BLUETOOTH_CONNECTIONS_FREE_REQUEST(80)"
                EspHomeMessageType.BLUETOOTH_CONNECTIONS_FREE_RESPONSE -> "BLUETOOTH_CONNECTIONS_FREE_RESPONSE(81)"
                EspHomeMessageType.BLUETOOTH_GATT_ERROR_RESPONSE -> "BLUETOOTH_GATT_ERROR_RESPONSE(82)"
                EspHomeMessageType.BLUETOOTH_GATT_WRITE_RESPONSE -> "BLUETOOTH_GATT_WRITE_RESPONSE(83)"
                EspHomeMessageType.BLUETOOTH_GATT_NOTIFY_RESPONSE -> "BLUETOOTH_GATT_NOTIFY_RESPONSE(84)"
                EspHomeMessageType.BLUETOOTH_DEVICE_PAIRING_RESPONSE -> "BLUETOOTH_DEVICE_PAIRING_RESPONSE(85)"
                EspHomeMessageType.BLUETOOTH_DEVICE_UNPAIRING_RESPONSE -> "BLUETOOTH_DEVICE_UNPAIRING_RESPONSE(86)"
                EspHomeMessageType.UNSUBSCRIBE_BLUETOOTH_LE_ADVERTISEMENTS_REQUEST -> "UNSUBSCRIBE_BLE_ADVERTISEMENTS_REQUEST(87)"
                EspHomeMessageType.BLUETOOTH_DEVICE_CLEAR_CACHE_RESPONSE -> "BLUETOOTH_DEVICE_CLEAR_CACHE_RESPONSE(88)"
                EspHomeMessageType.BLUETOOTH_LE_RAW_ADVERTISEMENTS_RESPONSE -> "BLUETOOTH_LE_RAW_ADVERTISEMENTS_RESPONSE(93)"
                EspHomeMessageType.BLUETOOTH_SCANNER_STATE_RESPONSE -> "BLUETOOTH_SCANNER_STATE_RESPONSE(126)"
                EspHomeMessageType.BLUETOOTH_SCANNER_SET_MODE_REQUEST -> "BLUETOOTH_SCANNER_SET_MODE_REQUEST(127)"
                else -> "type=$typeId"
            }
        }
    }

    private data class CompiledAdvertisementFilterRule(
        val macRegex: Regex?,
        val nameRegex: Regex?,
        val minRssi: Int,
        val hasRssiConstraint: Boolean,
    ) {
        fun matches(advertisement: RawAdvertisement): Boolean {
            if (hasRssiConstraint && advertisement.rssi < minRssi) {
                return false
            }

            if (macRegex != null) {
                val macAddress = ProxyIdentity.longToMac(advertisement.address)
                if (!macRegex.containsMatchIn(macAddress)) {
                    return false
                }
            }

            if (nameRegex != null) {
                val name = advertisement.name.orEmpty()
                if (!nameRegex.containsMatchIn(name)) {
                    return false
                }
            }

            return true
        }
    }

    private data class DedupState(
        val forwardedAtMs: Long,
        val payloadHash: Int,
        val addressType: Int,
        val name: String?,
    )

    private data class DiscoveryThrottleState(
        var lastSeenAtMs: Long,
        var lastForwardedAtMs: Long,
        var pendingAdvertisement: RawAdvertisement?,
    )

    private data class AdvertisementFingerprint(
        val payloadHash: Int,
        val payloadSize: Int,
        val addressType: Int,
        val name: String?,
    )

    private data class PendingQueueStats(
        val pendingSize: Int,
        val pendingCapacity: Int,
        val pendingUtilizationPercent: Int,
        val observedFingerprintCount: Int,
    )

    private fun RawAdvertisement.fingerprint(): AdvertisementFingerprint {
        return AdvertisementFingerprint(
            payloadHash = data.contentHashCode(),
            payloadSize = data.size,
            addressType = addressType,
            name = name,
        )
    }

}
