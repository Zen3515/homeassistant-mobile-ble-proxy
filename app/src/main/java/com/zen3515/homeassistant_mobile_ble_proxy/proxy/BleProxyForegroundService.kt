package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zen3515.homeassistant_mobile_ble_proxy.MainActivity
import com.zen3515.homeassistant_mobile_ble_proxy.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class BleProxyForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settingsRepository by lazy { SettingsRepository(applicationContext) }
    private val settingsMutationLock = Any()

    @Volatile
    private var started = false

    private var currentSettings = ProxySettings()
    private var macAddress: String = "00:00:00:00:00:00"

    private var scannerEngine: BluetoothScanEngine? = null
    private var apiServer: EspHomeApiServer? = null
    private var nsdAdvertiser: EspHomeNsdAdvertiser? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var scannerHealthJob: Job? = null
    private var wakeLockRenewalJob: Job? = null
    private var settingsSyncJob: Job? = null
    private var screenReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ProxyServiceController.ACTION_STOP -> {
                logRuntime("Stop requested")
                stopProxy()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ProxyServiceController.ACTION_START, null -> {
                logRuntime("Start requested")
                ensureNotificationChannel()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val connectedDeviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    val hasFineLocationPermission = ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                    val locationType = if (hasFineLocationPermission) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    } else {
                        0
                    }
                    val preferredType = connectedDeviceType or locationType
                    runCatching {
                        startForeground(
                            NOTIFICATION_ID,
                            buildNotification("Starting proxy"),
                            preferredType,
                        )
                    }.onFailure {
                        logRuntime(
                            "Foreground type $preferredType failed (${it.message}); " +
                                "falling back to connectedDevice only",
                        )
                        startForeground(
                            NOTIFICATION_ID,
                            buildNotification("Starting proxy"),
                            connectedDeviceType,
                        )
                    }
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification("Starting proxy"))
                }
                scope.launch {
                    startProxyIfNeeded()
                }
                return START_STICKY
            }

            else -> return START_STICKY
        }
    }

    override fun onDestroy() {
        stopProxy()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun startProxyIfNeeded() {
        if (started) {
            logRuntime("Start ignored (already running)")
            return
        }

        started = true
        registerScreenReceiver()
        acquireWakeLockIfNeeded()
        startWakeLockRenewal()
        ProxyRuntimeState.resetCounters()
        logRuntime("Initializing proxy runtime")
        currentSettings = settingsRepository.settings.first()
        if (!isBatteryOptimizationIgnored()) {
            logRuntime("Battery optimization is enabled; unrestricted battery is recommended for lock-screen BLE scanning")
        }
        macAddress = ProxyIdentity.resolveMacAddress(
            context = applicationContext,
            overrideMac = currentSettings.bluetoothMacOverride,
        )
        val enabledFilterCount = currentSettings.advertisementFilters.count { it.enabled }
        logRuntime(
            "Loaded settings: node=${currentSettings.nodeName}, port=${currentSettings.apiPort}, " +
                "scanner=${currentSettings.scannerMode.name.lowercase()}, " +
                "flush=${currentSettings.advertisementFlushIntervalMs}ms, " +
                "dedup=${currentSettings.advertisementDedupWindowMs}ms, " +
                "discovery_throttle=${currentSettings.advertisementDiscoveryThrottleIntervalMs}ms, " +
                "watchdog_interval=${currentSettings.scannerHealthCheckIntervalMs}ms, " +
                "low_rate_checks=${currentSettings.scannerLowRateConsecutiveChecks}, " +
                "nsd=${currentSettings.nsdInterfaceMode.name.lowercase()}, " +
                "filters=${if (enabledFilterCount == 0) "allow-all" else "$enabledFilterCount"}, " +
                "lock_targets=${currentSettings.lockScreenScanTargets.size}, " +
                "auto_add=${if (currentSettings.autoAddMatchedDevicesToLockScreenTargets) "on" else "off"}, " +
                "encryption=${if (currentSettings.espHomeApiEncryptionKey.isBlank()) "off" else "on"}",
        )

        scannerEngine = BluetoothScanEngine(
            context = applicationContext,
            lockScreenScanTargets = currentSettings.lockScreenScanTargets,
            onAdvertisement = { advertisement ->
                apiServer?.publishAdvertisement(advertisement)
            },
            onStateChanged = { state ->
                ProxyRuntimeState.setScannerState(state)
                logRuntime("Scanner state: ${state.name.lowercase()}")
                apiServer?.onScannerStateChanged(state)
                updateNotification("Scanner: ${state.name.lowercase()}")
            },
            onError = { message ->
                ProxyRuntimeState.setError(message)
                logRuntime("Scanner error: $message")
                updateNotification(message)
            },
            onLog = { message ->
                logRuntime("Scanner info: $message")
            },
        )

        nsdAdvertiser = EspHomeNsdAdvertiser(
            context = applicationContext,
            onError = { message ->
                ProxyRuntimeState.setError(message)
                logRuntime("mDNS error: $message")
                updateNotification(message)
            },
            onLog = { message ->
                logRuntime("mDNS info: $message")
            },
        )

        runCatching {
            val server = EspHomeApiServer(
                context = applicationContext,
                settings = currentSettings,
                macAddress = macAddress,
                scannerEngine = scannerEngine!!,
                onAdvertisementMatchedFilters = { advertisement ->
                    handleMatchedAdvertisementForLockScreenTargets(advertisement)
                },
                onError = { message ->
                    ProxyRuntimeState.setError(message)
                    logRuntime("Server error: $message")
                    updateNotification(message)
                },
                onLog = { message ->
                    logRuntime(message)
                },
            )
            val port = server.start()
            apiServer = server
            nsdAdvertiser?.register(currentSettings, macAddress, port)
            ProxyRuntimeState.setServiceRunning(true, port)
            logRuntime("Proxy listening on 0.0.0.0:$port (mac=$macAddress)")
            startSettingsSync()
            startScannerHealthWatchdog()
            updateNotification("Listening on port $port")
        }.onFailure {
            started = false
            ProxyRuntimeState.setServiceRunning(false)
            ProxyRuntimeState.setError("Failed to start proxy: ${it.message}")
            logRuntime("Failed to start proxy: ${it.message}")
            updateNotification("Failed to start")
            releaseWakeLockIfHeld()
            stopSelf()
        }
    }

    private fun stopProxy() {
        logRuntime("Stopping proxy")
        scannerHealthJob?.cancel()
        scannerHealthJob = null
        wakeLockRenewalJob?.cancel()
        wakeLockRenewalJob = null
        settingsSyncJob?.cancel()
        settingsSyncJob = null

        scannerEngine?.shutdown()
        scannerEngine = null

        apiServer?.stop()
        apiServer = null

        nsdAdvertiser?.shutdown()
        nsdAdvertiser = null

        started = false
        ProxyRuntimeState.setServiceRunning(false)
        ProxyRuntimeState.setScannerState(RuntimeScannerState.IDLE)
        unregisterScreenReceiver()
        releaseWakeLockIfHeld()
        logRuntime("Proxy stopped")
    }

    private fun startScannerHealthWatchdog() {
        scannerHealthJob?.cancel()
        val checkIntervalMs = currentSettings.scannerHealthCheckIntervalMs.toLong()
        scannerHealthJob = scope.launch {
            while (isActive && started) {
                delay(checkIntervalMs)
                val restarted = runCatching {
                    apiServer?.recoverScannerIfStalled(SCANNER_STALL_TIMEOUT_MS) ?: false
                }.getOrElse { error ->
                    logRuntime("Scanner watchdog error: ${error.message}")
                    false
                }
                if (restarted) {
                    logRuntime("Scanner watchdog restarted scanner after stall")
                }
            }
        }
    }

    private fun startSettingsSync() {
        settingsSyncJob?.cancel()
        settingsSyncJob = scope.launch {
            settingsRepository.settings.collectLatest { updatedSettings ->
                val previousSettings = synchronized(settingsMutationLock) {
                    val previous = currentSettings
                    currentSettings = updatedSettings
                    previous
                }

                val scanner = scannerEngine
                scanner?.updateLockScreenScanTargets(updatedSettings.lockScreenScanTargets)

                val previousTargetFilters = previousSettings.lockScreenScanTargets
                val currentTargetFilters = updatedSettings.lockScreenScanTargets
                val targetsChanged = previousTargetFilters != currentTargetFilters
                if (targetsChanged && !isScreenInteractive()) {
                    val restarted = scanner?.restartForEnvironmentChange() ?: false
                    if (restarted) {
                        logRuntime("Restarted scanner to apply updated lock-screen scan targets")
                    }
                }
            }
        }
    }

    private fun acquireWakeLockIfNeeded() {
        val manager = getSystemService(PowerManager::class.java) ?: run {
            logRuntime("WakeLock unavailable: PowerManager is null")
            return
        }

        val lock = wakeLock ?: manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:ble_proxy",
        ).apply {
            setReferenceCounted(false)
            wakeLock = this
        }

        if (!lock.isHeld) {
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            logRuntime("Acquired partial WakeLock (${WAKE_LOCK_TIMEOUT_MS}ms timeout)")
        }
    }

    private fun startWakeLockRenewal() {
        wakeLockRenewalJob?.cancel()
        wakeLockRenewalJob = scope.launch {
            while (isActive && started) {
                delay(WAKE_LOCK_RENEWAL_INTERVAL_MS)
                val lock = wakeLock ?: continue
                if (!lock.isHeld) {
                    acquireWakeLockIfNeeded()
                    logRuntime("Renewed partial WakeLock")
                }
            }
        }
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) {
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        acquireWakeLockIfNeeded()
                        logRuntime("Screen turned off; ensured partial WakeLock is held")
                        val scanner = scannerEngine ?: return
                        if (scanner.hasPlatformEligibleLockScreenTargets()) {
                            val restarted = scanner.restartForEnvironmentChange()
                            if (restarted) {
                                logRuntime("Restarted scanner to apply lock-screen scan targets")
                            }
                        } else {
                            logRuntime("No exact lock-screen scan targets configured; Android may stop broad BLE scanning while locked")
                        }
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        logRuntime("Screen turned on")
                        val scanner = scannerEngine ?: return
                        if (scanner.hasPlatformEligibleLockScreenTargets()) {
                            val restarted = scanner.restartForEnvironmentChange()
                            if (restarted) {
                                logRuntime("Restarted scanner to resume broad foreground scanning")
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        screenReceiver = receiver
    }

    private fun unregisterScreenReceiver() {
        val receiver = screenReceiver ?: return
        runCatching { unregisterReceiver(receiver) }
        screenReceiver = null
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        val powerManager = getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun isScreenInteractive(): Boolean {
        val powerManager = getSystemService(PowerManager::class.java) ?: return true
        return powerManager.isInteractive
    }

    private fun handleMatchedAdvertisementForLockScreenTargets(advertisement: RawAdvertisement) {
        val updatedSettings = synchronized(settingsMutationLock) {
            val snapshot = currentSettings
            if (!snapshot.autoAddMatchedDevicesToLockScreenTargets) {
                return@synchronized null
            }
            if (snapshot.advertisementFilters.none { it.enabled }) {
                return@synchronized null
            }

            val normalizedMac = ProxyIdentity.longToMac(advertisement.address)
            val alreadyTracked = snapshot.lockScreenScanTargets.any { existing ->
                ProxyIdentity.normalizeMacAddress(existing.macAddress) == normalizedMac
            }
            if (alreadyTracked) {
                return@synchronized null
            }

            val updated = snapshot.copy(
                lockScreenScanTargets = snapshot.lockScreenScanTargets + LockScreenScanTarget(
                    id = UUID.randomUUID().toString(),
                    macAddress = normalizedMac,
                    name = advertisement.name.orEmpty().trim(),
                ),
            )
            currentSettings = updated
            updated
        } ?: return

        scannerEngine?.updateLockScreenScanTargets(updatedSettings.lockScreenScanTargets)
        logRuntime(
            "Added lock-screen scan target from matched advertisement: " +
                ProxyIdentity.longToMac(advertisement.address) +
                advertisement.name?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(),
        )
        if (!isScreenInteractive()) {
            val restarted = scannerEngine?.restartForEnvironmentChange() ?: false
            if (restarted) {
                logRuntime("Restarted scanner after auto-adding lock-screen scan target")
            }
        }
        scope.launch {
            settingsRepository.save(updatedSettings)
        }
    }

    private fun releaseWakeLockIfHeld() {
        val lock = wakeLock ?: return
        if (!lock.isHeld) {
            return
        }
        runCatching {
            lock.release()
            logRuntime("Released partial WakeLock")
        }.onFailure {
            logRuntime("Failed to release WakeLock: ${it.message}")
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.proxy_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.proxy_notification_channel_description)
            setShowBadge(false)
        }

        manager.createNotificationChannel(channel)
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String): android.app.Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, BleProxyForegroundService::class.java).apply {
            action = ProxyServiceController.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.proxy_notification_title))
            .setContentText(status)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .addAction(0, getString(R.string.stop_proxy), stopPendingIntent)
            .build()
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "ble_proxy_channel"
        private const val NOTIFICATION_ID = 1100
        private const val SCANNER_STALL_TIMEOUT_MS = 45_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L
        private const val WAKE_LOCK_RENEWAL_INTERVAL_MS = 5 * 60 * 1000L
    }

    private fun logRuntime(message: String) {
        ProxyRuntimeState.appendLog(message)
    }
}
