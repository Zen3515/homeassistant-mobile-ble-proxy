package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.location.LocationManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class RawAdvertisement(
    val address: Long,
    val rssi: Int,
    val addressType: Int,
    val data: ByteArray,
    val name: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawAdvertisement) return false

        return address == other.address &&
            rssi == other.rssi &&
            addressType == other.addressType &&
            data.contentEquals(other.data) &&
            name == other.name
    }

    override fun hashCode(): Int {
        var result = address.hashCode()
        result = 31 * result + rssi
        result = 31 * result + addressType
        result = 31 * result + data.contentHashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        return result
    }
}

class BluetoothScanEngine(
    private val context: Context,
    managedTargetDevices: List<ManagedTargetDevice>,
    private val onAdvertisement: (RawAdvertisement) -> Unit,
    private val onStateChanged: (RuntimeScannerState) -> Unit,
    private val onError: (String) -> Unit,
    private val onLog: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bluetoothManager: BluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val powerManager: PowerManager? = context.getSystemService(PowerManager::class.java)
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager.adapter

    private val retryLock = Any()
    private val managedTargetDevicesLock = Any()

    @Volatile
    private var scanner: BluetoothLeScanner? = null

    @Volatile
    private var running = false

    @Volatile
    private var shouldRun = false

    @Volatile
    private var retryAttempt = 0

    private var retryJob: Job? = null
    private var currentMode: ScannerMode = ScannerMode.PASSIVE
    @Volatile
    private var lastFilterDescription: String = "broad"
    private var managedTargetDevices: List<ManagedTargetDevice> = managedTargetDevices

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (result in results) {
                handleScanResult(result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            running = false
            scanner = null
            onStateChanged(RuntimeScannerState.FAILED)
            val failureName = scanFailureName(errorCode)
            onError("BLE scan failed with code $errorCode ($failureName)")
            scheduleScanRetry(errorCode, failureName)
        }
    }

    private fun handleScanResult(result: ScanResult) {
        runCatching {
            val mac = result.device?.address ?: return
            val rawPayload = result.scanRecord?.bytes ?: ByteArray(0)
            val payload = sanitizeAdvertisementPayload(rawPayload) ?: return
            onAdvertisement(
                RawAdvertisement(
                    address = ProxyIdentity.macToLong(mac),
                    rssi = result.rssi,
                    addressType = 0,
                    data = payload,
                    name = result.scanRecord?.deviceName,
                ),
            )
        }.onFailure {
            onError("Failed to process BLE scan result: ${it.message}")
        }
    }

    fun setMode(mode: ScannerMode) {
        val shouldRestart = running && currentMode != mode
        currentMode = mode
        if (shouldRestart) {
            stop()
            start(mode)
        }
    }

    fun updateManagedTargetDevices(targets: List<ManagedTargetDevice>) {
        synchronized(managedTargetDevicesLock) {
            managedTargetDevices = targets
        }
    }

    fun hasPlatformEligibleManagedTargetDevices(): Boolean {
        return buildPlatformScanFilters(currentManagedTargetDevices()).isNotEmpty()
    }

    fun restartForEnvironmentChange(): Boolean {
        if (!running) {
            return false
        }
        stop()
        return start(currentMode)
    }

    fun start(mode: ScannerMode = currentMode): Boolean {
        return startInternal(mode, fromRetry = false)
    }

    private fun startInternal(mode: ScannerMode, fromRetry: Boolean): Boolean {
        currentMode = mode
        shouldRun = true
        if (!fromRetry) {
            cancelPendingRetry()
        }
        if (running) {
            return true
        }

        val scanPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            hasAnyLocationPermission()
        }
        if (!scanPermissionGranted) {
            onError("Missing Bluetooth scan permission")
            onStateChanged(RuntimeScannerState.FAILED)
            return false
        }

        if (!hasAnyLocationPermission()) {
            onLog("Location permission not granted; some devices may suppress BLE scan results")
        }
        if (!isLocationServiceEnabled()) {
            onLog("Location service is disabled; some devices may suppress BLE scan results")
        }

        val localAdapter = adapter
        if (localAdapter == null || !localAdapter.isEnabled) {
            onError("Bluetooth adapter is unavailable or disabled")
            onStateChanged(RuntimeScannerState.FAILED)
            return false
        }

        val localScanner = localAdapter.bluetoothLeScanner
        if (localScanner == null) {
            onError("Bluetooth LE scanner not available")
            onStateChanged(RuntimeScannerState.FAILED)
            return false
        }

        val settings = buildScanSettings(mode)
        val interactive = isDeviceInteractive()
        val platformScanFilters = if (interactive) {
            emptyList()
        } else {
            buildPlatformScanFilters(currentManagedTargetDevices())
        }
        val filters = platformScanFilters.ifEmpty { null }

        return runCatching {
            onStateChanged(RuntimeScannerState.STARTING)
            val scanModeLabel = when (mode) {
                ScannerMode.PASSIVE -> "low_power"
                ScannerMode.ACTIVE -> "low_latency"
            }
            lastFilterDescription = when {
                interactive -> "broad"
                filters == null -> "screen-off broad"
                else -> "screen-off targeted"
            }
            onLog(
                "BLE scan profile: interactive=${if (interactive) "on" else "off"}, " +
                    "mode=${mode.name.lowercase()}, scan_mode=$scanModeLabel, filter_mode=$lastFilterDescription",
            )
            if (interactive) {
                onLog("BLE scan using broad discovery because the screen is on")
            } else if (filters == null) {
                onLog("BLE scan has no platform-eligible managed target; Android may stop it when the screen turns off")
            } else {
                onLog("BLE scan using ${filters.size} managed target platform filter(s)")
            }
            localScanner.startScan(filters, settings, callback)
            onLog("BLE scan started with callback delivery")
            scanner = localScanner
            running = true
            retryAttempt = 0
            onStateChanged(RuntimeScannerState.RUNNING)
            if (fromRetry) {
                onLog("BLE scan restarted after failure")
            }
            true
        }.getOrElse {
            running = false
            scanner = null
            onStateChanged(RuntimeScannerState.FAILED)
            val detail = it.message ?: it.javaClass.simpleName
            onError("Failed to start BLE scan: $detail")
            scheduleScanRetry(START_SCAN_FAILED_ERROR_CODE, "START_SCAN_EXCEPTION")
            false
        }
    }

    fun stop() {
        shouldRun = false
        retryAttempt = 0
        cancelPendingRetry()
        if (!running) {
            scanner = null
            onStateChanged(RuntimeScannerState.STOPPED)
            return
        }

        onStateChanged(RuntimeScannerState.STOPPING)
        val scanPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            hasAnyLocationPermission()
        }
        if (!scanPermissionGranted) {
            onLog("Skipping stopScan because Bluetooth scan permission is not granted")
        } else {
            runCatching {
                scanner?.stopScan(callback)
            }.onFailure {
                onError("Failed to stop BLE scan: ${it.message}")
            }
        }

        scanner = null
        running = false
        onStateChanged(RuntimeScannerState.STOPPED)
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    private fun scheduleScanRetry(errorCode: Int, failureName: String) {
        if (!shouldRun) {
            return
        }

        synchronized(retryLock) {
            val existingRetry = retryJob
            if (existingRetry != null && existingRetry.isActive) {
                return
            }
            retryAttempt += 1
            val attempt = retryAttempt
            onLog(
                "Retrying BLE scan in ${SCAN_FAILED_RETRY_DELAY_MS}ms " +
                    "after $failureName (code=$errorCode, attempt=$attempt)",
            )
            retryJob = scope.launch {
                delay(SCAN_FAILED_RETRY_DELAY_MS)
                synchronized(retryLock) {
                    retryJob = null
                }
                if (!shouldRun || running) {
                    return@launch
                }
                startInternal(currentMode, fromRetry = true)
            }
        }
    }

    private fun cancelPendingRetry() {
        synchronized(retryLock) {
            retryJob?.cancel()
            retryJob = null
        }
    }

    private fun scanFailureName(errorCode: Int): String {
        return when (errorCode) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "SCAN_FAILED_ALREADY_STARTED"
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED"
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "SCAN_FAILED_INTERNAL_ERROR"
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "SCAN_FAILED_FEATURE_UNSUPPORTED"
            else -> "UNKNOWN_ERROR"
        }
    }

    private fun hasAnyLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun isLocationServiceEnabled(): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    private fun isDeviceInteractive(): Boolean {
        return powerManager?.isInteractive ?: true
    }

    private fun buildScanSettings(mode: ScannerMode): ScanSettings {
        val scanMode = when (mode) {
            ScannerMode.PASSIVE -> ScanSettings.SCAN_MODE_LOW_POWER
            ScannerMode.ACTIVE -> ScanSettings.SCAN_MODE_LOW_LATENCY
        }
        return ScanSettings.Builder()
            .setScanMode(scanMode)
            .setReportDelay(0)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
    }

    private fun currentManagedTargetDevices(): List<ManagedTargetDevice> {
        return synchronized(managedTargetDevicesLock) { managedTargetDevices }
    }

    private fun buildPlatformScanFilters(targets: List<ManagedTargetDevice>): List<ScanFilter> {
        if (targets.isEmpty()) {
            return emptyList()
        }
        val filters = mutableListOf<ScanFilter>()
        for (target in targets) {
            if (!target.enableLockScreenScan) continue

            val exactMac = ProxyIdentity.normalizeMacAddress(target.macAddress)
            val exactName = target.name.trim().ifEmpty { null }
            if (exactMac == null && exactName == null) {
                continue
            }
            val builder = ScanFilter.Builder()
            if (exactMac != null) {
                builder.setDeviceAddress(exactMac)
            }
            if (exactName != null) {
                builder.setDeviceName(exactName)
            }
            filters += builder.build()
        }
        return filters.distinctBy { filter ->
            "${filter.deviceAddress.orEmpty()}|${filter.deviceName.orEmpty()}"
        }
    }

    private fun sanitizeAdvertisementPayload(raw: ByteArray): ByteArray? {
        if (raw.isEmpty()) {
            return raw
        }

        val output = ByteArrayOutputStream(MAX_RAW_ADVERTISEMENT_LENGTH)
        var index = 0
        while (index < raw.size) {
            val length = raw[index].toInt() and 0xFF
            if (length == 0) {
                // Zero-length AD structure indicates end-of-data/padding.
                break
            }

            val next = index + 1 + length
            if (next > raw.size) {
                // Malformed payload from platform scanner.
                return null
            }
            if (next > MAX_RAW_ADVERTISEMENT_LENGTH) {
                // ESPHome proxy payload supports up to 62 bytes total.
                break
            }

            output.write(raw, index, next - index)
            index = next
        }

        return output.toByteArray()
    }

    companion object {
        private const val MAX_RAW_ADVERTISEMENT_LENGTH = 62
        private const val SCAN_FAILED_RETRY_DELAY_MS = 5_000L
        private const val START_SCAN_FAILED_ERROR_CODE = -1
    }
}
