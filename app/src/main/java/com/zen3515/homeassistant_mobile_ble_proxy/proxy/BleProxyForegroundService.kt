package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import android.app.NotificationChannel
import android.app.KeyguardManager
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
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class BleProxyForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settingsRepository by lazy { SettingsRepository(applicationContext) }
    private val settingsMutationLock = Any()
    private val lifecycleCoordinator = ServiceLifecycleCoordinator()

    @Volatile
    private var started = false

    private var currentSettings = ProxySettings()
    private var macAddress: String = "00:00:00:00:00:00"
    private var listeningPort: Int = 0

    private var scannerEngine: BluetoothScanEngine? = null
    private var apiServer: EspHomeApiServer? = null
    private var nsdAdvertiser: EspHomeNsdAdvertiser? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var scannerHealthJob: Job? = null
    private var wakeLockRenewalJob: Job? = null
    private var settingsSyncJob: Job? = null
    private var startupJob: Job? = null
    private var screenReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CrashDiagnostics.recordLifecycle(
            "service command=${intent?.action ?: "restart"} startId=$startId",
        )
        when (intent?.action) {
            ProxyServiceController.ACTION_STOP -> {
                CrashDiagnostics.recordLifecycle("service stop-command begin startId=$startId")
                logRuntime("Stop requested")
                val stopGeneration = lifecycleCoordinator.requestStop()
                cancelStartup()
                stopProxy()
                stopForeground(STOP_FOREGROUND_REMOVE)
                lifecycleCoordinator.markStopped(stopGeneration)
                stopSelfResult(startId)
                CrashDiagnostics.recordLifecycle("service stop-command complete startId=$startId")
                return START_NOT_STICKY
            }

            ProxyServiceController.ACTION_START, null -> {
                logRuntime("Start requested")
                val token = lifecycleCoordinator.requestStart(startId)
                if (token == null) {
                    CrashDiagnostics.recordLifecycle("service startup ignored startId=$startId")
                    logRuntime("Start ignored (already starting or running)")
                    return START_STICKY
                }

                if (!promoteToForeground(startId)) {
                    lifecycleCoordinator.markStartFailed(token)
                    started = false
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }

                started = true
                startupJob = scope.launch { startProxy(token) }
                return START_STICKY
            }

            else -> return START_STICKY
        }
    }

    override fun onDestroy() {
        CrashDiagnostics.recordLifecycle("service onDestroy begin")
        val stopGeneration = lifecycleCoordinator.requestStop()
        cancelStartup()
        stopProxy()
        lifecycleCoordinator.markStopped(stopGeneration)
        scope.cancel()
        CrashDiagnostics.recordLifecycle("service onDestroy complete")
        super.onDestroy()
    }

    private fun promoteToForeground(startId: Int): Boolean {
        return runCatching {
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
                }.getOrElse { preferredError ->
                    logRuntime(
                        "Foreground type $preferredType failed (${preferredError.message}); " +
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
        }.fold(
            onSuccess = {
                CrashDiagnostics.recordLifecycle("service foreground active startId=$startId")
                true
            },
            onFailure = { error ->
                val detail = error.message ?: error.javaClass.simpleName
                CrashDiagnostics.recordLifecycle(
                    "service foreground failed startId=$startId type=${error.javaClass.simpleName}",
                )
                ProxyRuntimeState.setError("Unable to start foreground proxy: $detail")
                logRuntime("Unable to start foreground proxy: $detail")
                false
            },
        )
    }

    private fun cancelStartup() {
        startupJob?.cancel()
        startupJob = null
    }

    private suspend fun startProxy(token: ServiceLifecycleCoordinator.StartToken) {
        val startId = token.startId
        CrashDiagnostics.recordLifecycle(
            "service startup begin startId=$startId generation=${token.generation}",
        )
        try {
            registerScreenReceiver()
            acquireWakeLockIfNeeded()
            startWakeLockRenewal()
            ProxyRuntimeState.resetCounters()
            logRuntime("Initializing proxy runtime")
            currentSettings = settingsRepository.settings.first()
            coroutineContext.ensureActive()
            if (!lifecycleCoordinator.isCurrent(token)) {
                return
            }
            CrashDiagnostics.recordLifecycle("service startup settings-loaded startId=$startId")
            if (!isBatteryOptimizationIgnored()) {
                logRuntime(
                    "Battery optimization is enabled; unrestricted battery is recommended for " +
                        "lock-screen BLE scanning",
                )
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
                    "nsd_order=${currentSettings.nsdTransportOrder.joinToString(">") { it.name.lowercase() }}, " +
                    "filters=${if (enabledFilterCount == 0) "allow-all" else "$enabledFilterCount"}, " +
                    "lock_targets=${currentSettings.managedTargetDevices.size}, " +
                    "auto_add=${if (currentSettings.autoAddMatchedDevicesToLockScreenTargets) "on" else "off"}, " +
                    "gatt_notify_log=${if (currentSettings.verboseGattNotifyDataLogging) "verbose" else "normal"}, " +
                    "encryption=${if (currentSettings.espHomeApiEncryptionKey.isBlank()) "off" else "on"}",
            )

            scannerEngine = BluetoothScanEngine(
                context = applicationContext,
                managedTargetDevices = currentSettings.managedTargetDevices,
                onAdvertisement = { advertisement ->
                    apiServer?.publishAdvertisement(advertisement)
                },
                onStateChanged = { state ->
                    ProxyRuntimeState.setScannerState(state)
                    logRuntime("Scanner state: ${state.name.lowercase()}")
                    apiServer?.onScannerStateChanged(state)
                    updateNotification("Scanner: ${state.name.lowercase()}")
                },
                onProfileChanged = { profile ->
                    ProxyRuntimeState.setActiveScanProfile(profile)
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
            reconcileScannerProfile(reason = "startup")
            CrashDiagnostics.recordLifecycle("service startup scanner-created startId=$startId")

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

            val server = EspHomeApiServer(
                context = applicationContext,
                settings = currentSettings,
                macAddress = macAddress,
                scannerEngine = scannerEngine!!,
                isVerboseGattNotifyDataLoggingEnabled = {
                    synchronized(settingsMutationLock) {
                        currentSettings.verboseGattNotifyDataLogging
                    }
                },
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
            apiServer = server
            val port = server.start()
            CrashDiagnostics.recordLifecycle("service startup server-bound startId=$startId")
            listeningPort = port
            nsdAdvertiser?.register(currentSettings, macAddress, port)
            if (!lifecycleCoordinator.markRunning(token)) {
                stopProxy()
                return
            }
            ProxyRuntimeState.setServiceRunning(true, port)
            BleProxyTileService.onServiceRunningChanged(applicationContext, true)
            logRuntime("Proxy listening on 0.0.0.0:$port (mac=$macAddress)")
            startSettingsSync()
            startScannerHealthWatchdog()
            updateNotification("Listening on port $port")
            CrashDiagnostics.recordLifecycle("service startup complete startId=$startId")
        } catch (cancelled: CancellationException) {
            CrashDiagnostics.recordLifecycle(
                "service startup cancelled startId=$startId generation=${token.generation}",
            )
            throw cancelled
        } catch (error: Exception) {
            val cleanupStartId = lifecycleCoordinator.latestStartId(token)
            if (!lifecycleCoordinator.markStartFailed(token)) {
                CrashDiagnostics.recordLifecycle(
                    "service stale startup failure ignored startId=$startId generation=${token.generation}",
                )
                return
            }
            CrashDiagnostics.recordLifecycle(
                "service startup failed startId=$startId type=${error.javaClass.simpleName}",
            )
            val detail = error.message ?: error.javaClass.simpleName
            ProxyRuntimeState.setError("Failed to start proxy: $detail")
            logRuntime("Failed to start proxy: $detail")
            stopProxy()
            stopForeground(STOP_FOREGROUND_REMOVE)
            cleanupStartId?.let(::stopSelfResult)
        } finally {
            if (lifecycleCoordinator.isCurrent(token)) {
                startupJob = null
            }
        }
    }

    private fun stopProxy() {
        CrashDiagnostics.recordLifecycle("service teardown begin")
        logRuntime("Stopping proxy")
        scannerHealthJob?.cancel()
        scannerHealthJob = null
        wakeLockRenewalJob?.cancel()
        wakeLockRenewalJob = null
        settingsSyncJob?.cancel()
        settingsSyncJob = null

        val server = apiServer
        apiServer = null
        runCatching { server?.stop() }.onFailure { error ->
            logRuntime("API server teardown failed: ${error.message ?: error.javaClass.simpleName}")
        }

        val scanner = scannerEngine
        scannerEngine = null
        runCatching { scanner?.shutdown() }.onFailure { error ->
            logRuntime("Scanner teardown failed: ${error.message ?: error.javaClass.simpleName}")
        }
        listeningPort = 0

        val advertiser = nsdAdvertiser
        nsdAdvertiser = null
        runCatching { advertiser?.shutdown() }.onFailure { error ->
            logRuntime("mDNS teardown failed: ${error.message ?: error.javaClass.simpleName}")
        }

        started = false
        ProxyRuntimeState.setServiceRunning(false)
        BleProxyTileService.onServiceRunningChanged(applicationContext, false)
        ProxyRuntimeState.setScannerState(RuntimeScannerState.IDLE)
        ProxyRuntimeState.setDesiredScanProfile(null)
        ProxyRuntimeState.setActiveScanProfile(null)
        unregisterScreenReceiver()
        releaseWakeLockIfHeld()
        logRuntime("Proxy stopped")
        CrashDiagnostics.recordLifecycle("service teardown complete")
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
                scanner?.updateManagedTargetDevices(updatedSettings.managedTargetDevices)

                val previousTargetFilters = previousSettings.managedTargetDevices
                val currentTargetFilters = updatedSettings.managedTargetDevices
                val targetsChanged = previousTargetFilters != currentTargetFilters
                if (targetsChanged) {
                    reconcileScannerProfile(
                        reason = "managed_target_settings_updated",
                        forceRestartIfSameProfile = true,
                    )
                }

                val nsdSettingsChanged = previousSettings.nodeName != updatedSettings.nodeName ||
                    previousSettings.nsdInterfaceMode != updatedSettings.nsdInterfaceMode ||
                    previousSettings.nsdTransportOrder != updatedSettings.nsdTransportOrder
                val port = listeningPort
                if (nsdSettingsChanged && port > 0) {
                    logRuntime(
                        "mDNS settings updated; re-registering " +
                            "(mode=${updatedSettings.nsdInterfaceMode.name.lowercase()}, " +
                            "order=${updatedSettings.nsdTransportOrder.joinToString(">") { it.name.lowercase() }})",
                    )
                    nsdAdvertiser?.register(updatedSettings, macAddress, port)
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
                        reconcileScannerProfile(reason = "screen_off")
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        logRuntime("Screen turned on")
                        reconcileScannerProfile(reason = "screen_on")
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        logRuntime("User present")
                        reconcileScannerProfile(reason = "user_present")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
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

    private fun isKeyguardLocked(): Boolean {
        val keyguardManager = getSystemService(KeyguardManager::class.java) ?: return false
        return keyguardManager.isKeyguardLocked
    }

    private fun platformEligibleLockScreenTargetCount(): Int {
        return BluetoothScanEngine.platformEligibleTargetCount(currentSettings.managedTargetDevices)
    }

    private fun computeDesiredScanProfile(): ScanProfile {
        val interactive = isScreenInteractive()
        val keyguardLocked = isKeyguardLocked()
        val eligibleTargetCount = platformEligibleLockScreenTargetCount()
        return if (interactive && !keyguardLocked) {
            ScanProfile.UNLOCKED_BROAD
        } else if (eligibleTargetCount > 0) {
            ScanProfile.LOCKED_TARGETED
        } else {
            ScanProfile.LOCKED_BROAD_FALLBACK
        }
    }

    private fun reconcileScannerProfile(
        reason: String,
        forceRestartIfSameProfile: Boolean = false,
    ) {
        val scanner = scannerEngine ?: return
        val interactive = isScreenInteractive()
        val keyguardLocked = isKeyguardLocked()
        val targetFilters = platformEligibleLockScreenTargetCount()
        val desiredProfile = computeDesiredScanProfile()
        val currentProfile = scanner.currentProfile()
        val shouldForceRestart =
            forceRestartIfSameProfile &&
                scanner.isRunning() &&
                desiredProfile == currentProfile &&
                desiredProfile == ScanProfile.LOCKED_TARGETED

        ProxyRuntimeState.setDesiredScanProfile(desiredProfile)

        logRuntime(
            "Scanner profile reconcile: reason=$reason interactive=${if (interactive) "on" else "off"} " +
                "keyguard_locked=$keyguardLocked target_filters=$targetFilters old=${currentProfile.name} " +
                "new=${desiredProfile.name} running=${scanner.isRunning()} force_restart=$shouldForceRestart",
        )

        val profileChanged = currentProfile != desiredProfile
        if (!profileChanged && !shouldForceRestart) {
            return
        }

        val restarted = scanner.restartForProfile(desiredProfile)
        if (scanner.isRunning()) {
            if (restarted) {
                logRuntime("Restarted scanner for profile ${desiredProfile.name} (reason=$reason)")
            } else {
                logRuntime("Scanner restart for profile ${desiredProfile.name} did not complete (reason=$reason)")
            }
        } else {
            logRuntime("Stored scanner profile ${desiredProfile.name} for next scanner start (reason=$reason)")
        }
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
            val alreadyTracked = snapshot.managedTargetDevices.any { existing ->
                ProxyIdentity.normalizeMacAddress(existing.macAddress) == normalizedMac
            }
            if (alreadyTracked) {
                return@synchronized null
            }

            val updated = snapshot.copy(
                managedTargetDevices = snapshot.managedTargetDevices + ManagedTargetDevice(
                    id = UUID.randomUUID().toString(),
                    macAddress = normalizedMac,
                    name = advertisement.name.orEmpty().trim(),
                ),
            )
            currentSettings = updated
            updated
        } ?: return

        scannerEngine?.updateManagedTargetDevices(updatedSettings.managedTargetDevices)
        logRuntime(
            "Added lock-screen scan target from matched advertisement: " +
                ProxyIdentity.longToMac(advertisement.address) +
                advertisement.name?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(),
        )
        reconcileScannerProfile(
            reason = "auto_added_lock_screen_target",
            forceRestartIfSameProfile = true,
        )
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
        if (!started ||
            lifecycleCoordinator.state == ServiceLifecycleCoordinator.State.STOPPING ||
            lifecycleCoordinator.state == ServiceLifecycleCoordinator.State.STOPPED
        ) {
            return
        }
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
        internal const val NOTIFICATION_ID = 1100
        private const val SCANNER_STALL_TIMEOUT_MS = 45_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L
        private const val WAKE_LOCK_RENEWAL_INTERVAL_MS = 5 * 60 * 1000L
    }

    private fun logRuntime(message: String) {
        ProxyRuntimeState.appendLog(message)
    }
}
