package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Broadcasts raw BLE advertising payloads, mirroring the send loop in NicoIIT/esphome-ble_adv_proxy
 * (ble_adv_proxy.cpp: one packet advertised at a time for its requested duration, then the next).
 *
 * Android's public BLE advertiser API has no "raw bytes" mode: [AdvertiseData] is always
 * (re)serialized by the platform from structured fields, and the platform unconditionally
 * prepends its own Flags AD structure. Raw payloads are decomposed into AD structures and
 * replayed through the closest matching structured field (manufacturer data / service data /
 * service UUIDs), which is byte-compatible for the Manufacturer-Specific-Data-framed payloads
 * ble_adv devices use, but is not guaranteed to be a byte-for-byte reproduction of the input.
 */
internal class BleAdvertiseManager(
    private val context: Context,
    private val onError: (String) -> Unit = {},
    private val onLog: (String) -> Unit = {},
    /**
     * Scanning is paused for the duration of an advertising burst. A continuously running LE scan
     * competes with advertising for radio time, and on shared Wi-Fi/BT antennas it can starve
     * advertising badly enough that nothing reaches the target device.
     */
    private val onPauseScanning: (() -> BluetoothScanPauseToken)? = null,
    private val onResumeScanning: (BluetoothScanPauseToken) -> Boolean = { false },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bluetoothManager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)

    private val queueLock = Any()
    private val queue = ArrayDeque<RawAdvPacket>()
    private var pumpJob: Job? = null

    @Volatile
    private var shutdown = false

    data class RawAdvPacket(val payload: ByteArray, val durationMs: Int)

    fun enqueue(rawHex: String, durationMs: Float, repeat: Int) {
        if (shutdown) return
        val payload = runCatching { hexToBytes(rawHex) }.getOrElse {
            onError("Invalid raw adv hex payload '$rawHex': ${it.message}")
            return
        }
        if (payload.isEmpty()) {
            onError("Empty raw adv payload ignored")
            return
        }

        // The ESP32 emits `repeat` advertising events spaced `durationMs` apart, setting its
        // advertising interval to match the duration. Android's interval floor is 100ms
        // (ADVERTISE_MODE_LOW_LATENCY), so replaying a 30ms window per repeat usually puts
        // nothing on air at all. Advertise once across the whole span instead, padded so that
        // several advertising events actually fit inside it.
        val requestedMs = durationMs.toInt().coerceAtLeast(0) * repeat.coerceAtLeast(1)
        val windowMs = requestedMs.coerceIn(MIN_ADVERTISE_WINDOW_MS, MAX_ADVERTISE_WINDOW_MS)

        synchronized(queueLock) {
            // Home Assistant re-sends the same payload back-to-back; extend the pending window
            // rather than churning through start/stop cycles that each cost radio setup time.
            val pending = queue.peekLast()
            if (pending != null && pending.payload.contentEquals(payload)) {
                queue.removeLast()
                queue.addLast(
                    RawAdvPacket(
                        payload = payload,
                        durationMs = (pending.durationMs + windowMs).coerceAtMost(MAX_ADVERTISE_WINDOW_MS),
                    ),
                )
            } else if (queue.size < MAX_QUEUE_SIZE) {
                queue.addLast(RawAdvPacket(payload, windowMs))
            }
        }
        ensurePumpRunning()
    }

    fun shutdown() {
        if (shutdown) return
        shutdown = true
        pumpJob?.cancel()
        pumpJob = null
        synchronized(queueLock) { queue.clear() }
        stopAdvertisingQuietly()
        scope.cancel()
    }

    private fun ensurePumpRunning() {
        if (pumpJob?.isActive == true) return
        pumpJob = scope.launch { pump() }
    }

    private suspend fun pump() {
        var scanPauseToken: BluetoothScanPauseToken? = null
        try {
            while (!shutdown) {
                val packet = synchronized(queueLock) { queue.pollFirst() } ?: break
                if (scanPauseToken == null) {
                    scanPauseToken = onPauseScanning?.let { pause ->
                        runCatching { pause() }
                            .onFailure { onError("Failed to pause scan for advertising: ${it.message}") }
                            .getOrNull()
                    }
                    if (scanPauseToken?.wasRunning == true) {
                        onLog("Paused BLE scan for advertising burst")
                        // Give the controller a moment to actually tear the scan down.
                        delay(SCAN_TEARDOWN_SETTLE_MS)
                    }
                }
                sendPacketAndWait(packet)
            }
        } finally {
            val token = scanPauseToken
            if (token?.wasRunning == true) {
                val resumed = runCatching { onResumeScanning(token) }
                    .onFailure { onError("Failed to resume scan after advertising: ${it.message}") }
                    .getOrDefault(false)
                if (resumed) {
                    onLog(
                        "Restored BLE scan after advertising burst " +
                            "(profile=${token.profile.name.lowercase()}, mode=${token.mode.name.lowercase()})",
                    )
                } else {
                    onLog("BLE scan was not restored because it is no longer requested")
                }
            }
        }
    }

    private suspend fun sendPacketAndWait(packet: RawAdvPacket) {
        val advertiser = leAdvertiser()
        if (advertiser == null) {
            onError("BLE advertiser unavailable; dropping raw adv packet")
            return
        }
        if (!hasAdvertisePermission()) {
            onError("Missing BLUETOOTH_ADVERTISE permission; dropping raw adv packet")
            return
        }

        // Prefer the framing the vendor app itself uses (see buildServiceUuidAdvertiseData):
        // connectable ADV_IND, which is what these receivers actually accept.
        val serviceUuidData = buildServiceUuidAdvertiseData(packet.payload)
        val connectable = serviceUuidData != null
        val data = serviceUuidData ?: buildAdvertiseData(packet.payload)
        if (data == null) {
            onError("Raw adv payload had no reconstructable AD structures: ${formatHex(packet.payload)}")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(connectable)
            .build()

        val started = startAdvertisingBlocking(advertiser, settings, data)
        if (started) {
            try {
                val framing = if (connectable) "uuid16/ADV_IND" else "mfr-data/ADV_NONCONN_IND"
                onLog(
                    "Advertising raw adv for ${packet.durationMs}ms " +
                        "($framing): ${formatHex(packet.payload)}",
                )
                delay(packet.durationMs.toLong())
            } finally {
                // A cancelled pump must never leave Android advertising indefinitely.
                stopAdvertisingQuietly()
            }
        }
    }

    private suspend fun startAdvertisingBlocking(
        advertiser: BluetoothLeAdvertiser,
        settings: AdvertiseSettings,
        data: AdvertiseData,
    ): Boolean {
        val started = withTimeoutOrNull(ADVERTISE_START_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val cancelled = AtomicBoolean(false)
                val callbackCompleted = AtomicBoolean(false)
                val callback = object : AdvertiseCallback() {
                    override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                        if (!callbackCompleted.compareAndSet(false, true)) {
                            stopAdvertisingCallback(advertiser, this)
                            return
                        }
                        synchronized(advertisingLock) {
                            if (!cancelled.get() && !shutdown) {
                                activeAdvertisement = ActiveAdvertisement(advertiser, this)
                            }
                        }
                        if (cancelled.get() || shutdown) {
                            stopAdvertisingCallback(advertiser, this)
                        }
                        continuation.resumeWith(Result.success(true))
                    }

                    override fun onStartFailure(errorCode: Int) {
                        if (!callbackCompleted.compareAndSet(false, true)) return
                        onError("Failed to start raw adv broadcast (code=$errorCode)")
                        continuation.resumeWith(Result.success(false))
                    }
                }
                continuation.invokeOnCancellation {
                    cancelled.set(true)
                    stopAdvertisingCallback(advertiser, callback)
                }
                runCatching {
                    advertiser.startAdvertising(settings, data, callback)
                }.onFailure {
                    if (!callbackCompleted.compareAndSet(false, true)) return@onFailure
                    onError("startAdvertising threw: ${it.message}")
                    continuation.resumeWith(Result.success(false))
                }
            }
        }
        if (started == null) {
            onError("Timed out starting raw adv broadcast after ${ADVERTISE_START_TIMEOUT_MS}ms")
            return false
        }
        return started
    }

    private data class ActiveAdvertisement(
        val advertiser: BluetoothLeAdvertiser,
        val callback: AdvertiseCallback,
    )

    private val advertisingLock = Any()
    private var activeAdvertisement: ActiveAdvertisement? = null

    private fun stopAdvertisingQuietly() {
        val active = synchronized(advertisingLock) {
            activeAdvertisement.also { activeAdvertisement = null }
        } ?: return
        stopAdvertisingCallback(active.advertiser, active.callback)
    }

    private fun stopAdvertisingCallback(
        advertiser: BluetoothLeAdvertiser,
        callback: AdvertiseCallback,
    ) {
        synchronized(advertisingLock) {
            if (activeAdvertisement?.callback === callback) {
                activeAdvertisement = null
            }
        }
        if (!hasAdvertisePermission()) return
        runCatching { advertiser.stopAdvertising(callback) }
            .onFailure { onError("stopAdvertising threw: ${it.message}") }
    }

    private fun leAdvertiser(): BluetoothLeAdvertiser? {
        val adapter = bluetoothManager?.adapter ?: return null
        if (!adapter.isEnabled) return null
        return adapter.bluetoothLeAdvertiser
    }

    private fun hasAdvertisePermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        // Android cannot advertise faster than one event per 100ms, so a window shorter than a
        // few hundred ms risks transmitting nothing. ~3-4 advertising events per command.
        /** ADVERTISE_MODE_LOW_LATENCY, the fastest interval Android exposes. */
        private const val ADVERTISE_INTERVAL_MS = 100
        private const val SCAN_TEARDOWN_SETTLE_MS = 120L
        private const val ADVERTISE_START_TIMEOUT_MS = 5_000L

        /** 13 16-bit service UUIDs, matching the vendor app's framing. */
        private const val SERVICE_UUID_PAYLOAD_BYTES = 26
        private const val MIN_ADVERTISE_WINDOW_MS = 350
        private const val MAX_ADVERTISE_WINDOW_MS = 3_000
        private const val MAX_QUEUE_SIZE = 256

        private const val AD_TYPE_FLAGS = 0x01
        private const val AD_TYPE_INCOMPLETE_16_BIT_UUIDS = 0x02
        private const val AD_TYPE_COMPLETE_16_BIT_UUIDS = 0x03
        private const val AD_TYPE_SERVICE_DATA_16_BIT = 0x16
        private const val AD_TYPE_MANUFACTURER_DATA = 0xFF

        /** Splits a raw ADV byte sequence into (type, value) AD structures. */
        fun parseAdStructures(raw: ByteArray): List<Pair<Int, ByteArray>> {
            val structures = mutableListOf<Pair<Int, ByteArray>>()
            var index = 0
            while (index < raw.size) {
                val length = raw[index].toInt() and 0xFF
                if (length == 0) break
                val typeIndex = index + 1
                if (typeIndex >= raw.size) break
                val type = raw[typeIndex].toInt() and 0xFF
                val valueStart = typeIndex + 1
                val valueEnd = index + 1 + length
                if (valueEnd > raw.size) break
                structures += type to raw.copyOfRange(valueStart, valueEnd)
                index = valueEnd
            }
            return structures
        }

        /**
         * Reproduces the framing the FanLamp Pro app uses (com.allinktec.ble_plugin.BlePlugin).
         *
         * These receivers only accept connectable ADV_IND, but Android injects a 3-byte Flags
         * structure into connectable advertisements. Manufacturer-specific data costs 4 header
         * bytes (len + type + 2-byte company id), so 3 + 4 + 27 overflows the 31-byte limit and
         * Android rejects it with ADVERTISE_FAILED_DATA_TOO_LARGE.
         *
         * The vendor app sidesteps this by smuggling the command through as a list of 16-bit
         * service UUIDs, whose header is only 2 bytes:
         *
         *     02 01 06   Flags (injected by Android)      3
         *     1B 03      16-bit service UUID list + 26   28
         *                                                --
         *                                                31  exactly
         *
         * Android serialises each 16-bit UUID little-endian, so pairing bytes as
         * (hi = raw[i+1], lo = raw[i]) puts the original byte order back on air.
         *
         * Returns null when the payload cannot be expressed this way, leaving the caller to fall
         * back to non-connectable manufacturer-data framing.
         */
        fun buildServiceUuidAdvertiseData(raw: ByteArray): AdvertiseData? {
            if (raw.size < SERVICE_UUID_PAYLOAD_BYTES) return null
            // The app drops the leading AD header and keeps the trailing 26 bytes of the command.
            val payload = raw.copyOfRange(raw.size - SERVICE_UUID_PAYLOAD_BYTES, raw.size)

            val builder = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
            for (i in payload.indices step 2) {
                val uuid16 = ((payload[i + 1].toInt() and 0xFF) shl 8) or (payload[i].toInt() and 0xFF)
                builder.addServiceUuid(uuid16ToParcelUuid(uuid16))
            }
            return runCatching { builder.build() }.getOrNull()
        }

        fun buildAdvertiseData(raw: ByteArray): AdvertiseData? {
            val builder = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
            var addedAny = false

            for ((type, value) in parseAdStructures(raw)) {
                when (type) {
                    AD_TYPE_FLAGS -> {
                        // Android's advertiser always injects its own Flags structure; there is
                        // no public API to suppress or override it, so the input's is dropped.
                    }

                    AD_TYPE_MANUFACTURER_DATA -> {
                        if (value.size >= 2) {
                            val companyId = (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)
                            val manufacturerData = value.copyOfRange(2, value.size)
                            runCatching { builder.addManufacturerData(companyId, manufacturerData) }
                                .onSuccess { addedAny = true }
                        }
                    }

                    AD_TYPE_SERVICE_DATA_16_BIT -> {
                        if (value.size >= 2) {
                            val uuid16 = (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)
                            val serviceData = value.copyOfRange(2, value.size)
                            runCatching { builder.addServiceData(uuid16ToParcelUuid(uuid16), serviceData) }
                                .onSuccess { addedAny = true }
                        }
                    }

                    AD_TYPE_INCOMPLETE_16_BIT_UUIDS, AD_TYPE_COMPLETE_16_BIT_UUIDS -> {
                        var idx = 0
                        while (idx + 1 < value.size) {
                            val uuid16 = (value[idx].toInt() and 0xFF) or ((value[idx + 1].toInt() and 0xFF) shl 8)
                            runCatching { builder.addServiceUuid(uuid16ToParcelUuid(uuid16)) }
                                .onSuccess { addedAny = true }
                            idx += 2
                        }
                    }

                    else -> {
                        // No structured AdvertiseData field can carry this AD type; unsupported
                        // via Android's public advertiser API.
                    }
                }
            }

            return if (addedAny) builder.build() else null
        }

        private fun uuid16ToParcelUuid(uuid16: Int): ParcelUuid {
            return ParcelUuid.fromString(String.format(Locale.US, "0000%04x-0000-1000-8000-00805f9b34fb", uuid16))
        }

        fun hexToBytes(hex: String): ByteArray {
            val cleaned = hex.trim().filter { !it.isWhitespace() }
            require(cleaned.length % 2 == 0) { "hex string must have an even number of characters" }
            return ByteArray(cleaned.length / 2) { i ->
                cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }

        fun formatHex(bytes: ByteArray): String {
            return bytes.joinToString(separator = "") { String.format(Locale.US, "%02X", it) }
        }
    }
}
