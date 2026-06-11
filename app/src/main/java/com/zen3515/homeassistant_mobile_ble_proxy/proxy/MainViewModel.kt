package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class MainUiState(
    val settings: ProxySettings = ProxySettings(),
    val runtime: ProxyRuntimeSnapshot = ProxyRuntimeSnapshot(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val settingsRepository = SettingsRepository(appContext)
    private val initialSettings = ProxySettings(
        bluetoothMacOverride = ProxyIdentity.stableMacAddress(appContext),
        espHomeApiEncryptionKey = ProxyIdentity.stableNoisePsk(appContext),
    )

    private val settings = settingsRepository.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        initialSettings,
    )

    val uiState: StateFlow<MainUiState> = combine(
        settings,
        ProxyRuntimeState.state,
    ) { proxySettings, runtime ->
        MainUiState(settings = proxySettings, runtime = runtime)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MainUiState(settings = initialSettings),
    )

    init {
        refreshServiceRunningState()
    }

    fun saveSettings(settings: ProxySettings) {
        val sanitizedNodeName = settings.nodeName
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .ifBlank { ProxySettings().nodeName }

        val sanitizedFriendlyName = settings.friendlyName
            .trim()
            .ifBlank { ProxySettings().friendlyName }

        val sanitizedSettings = settings.copy(
            nodeName = sanitizedNodeName,
            friendlyName = sanitizedFriendlyName,
            apiPort = settings.apiPort.coerceIn(1024, 65535),
            bluetoothMacOverride = settings.bluetoothMacOverride.trim()
                .let { mac ->
                    if (mac.isBlank()) {
                        ""
                    } else {
                        ProxyIdentity.normalizeMacAddress(mac) ?: ""
                    }
                },
            espHomeApiEncryptionKey = settings.espHomeApiEncryptionKey.trim(),
            advertisementFlushIntervalMs = settings.advertisementFlushIntervalMs.coerceIn(50, 10_000),
            advertisementDedupWindowMs = settings.advertisementDedupWindowMs.coerceIn(0, 60_000),
            advertisementDiscoveryThrottleIntervalMs = settings.advertisementDiscoveryThrottleIntervalMs.coerceIn(0, 3_600_000),
            scannerHealthCheckIntervalMs = settings.scannerHealthCheckIntervalMs.coerceIn(5_000, 120_000),
            scannerLowRateConsecutiveChecks = settings.scannerLowRateConsecutiveChecks.coerceIn(1, 12),
            nsdInterfaceMode = if (settings.nsdInterfaceMode == NsdInterfaceMode.VPN) {
                NsdInterfaceMode.PREFERRED
            } else {
                settings.nsdInterfaceMode
            },
            nsdTransportOrder = NsdAdvertiseDefaults.sanitizeTransportOrder(settings.nsdTransportOrder),
            advertisementFilters = settings.advertisementFilters.map { rule ->
                rule.copy(
                    id = rule.id.ifBlank { UUID.randomUUID().toString() },
                    macRegex = rule.macRegex.trim(),
                    nameRegex = rule.nameRegex.trim(),
                    minRssi = rule.minRssi.coerceIn(-127, 0),
                )
            },
            managedTargetDevices = settings.managedTargetDevices.mapNotNull { target ->
                val mac = target.macAddress.trim()
                val sanitizedTarget = if (mac.isBlank()) {
                    target.copy(
                        id = target.id.ifBlank { UUID.randomUUID().toString() },
                        macAddress = "",
                        name = target.name.trim(),
                    )
                } else {
                    val normalizedMac = ProxyIdentity.normalizeMacAddress(mac) ?: return@mapNotNull null
                    target.copy(
                        id = target.id.ifBlank { UUID.randomUUID().toString() },
                        macAddress = normalizedMac,
                        name = target.name.trim(),
                    )
                }
                if (sanitizedTarget.macAddress.isBlank() && sanitizedTarget.name.isBlank()) {
                    return@mapNotNull null
                }
                sanitizedTarget
            }.distinctBy { target ->
                val macKey = target.macAddress.ifBlank { "-" }
                val nameKey = target.name.lowercase()
                "$macKey|$nameKey"
            },
        )

        viewModelScope.launch {
            settingsRepository.save(sanitizedSettings)
        }
    }

    fun setAutoStartOnBoot(enabled: Boolean) {
        updateSettings { it.copy(autoStartOnBoot = enabled) }
    }

    fun setProxyEnabled(enabled: Boolean) {
        if (enabled) {
            ProxyServiceController.start(appContext)
        } else {
            ProxyServiceController.stop(appContext)
        }
    }

    fun refreshServiceRunningState() {
        ProxyServiceController.reconcileObservedRunning(appContext)
    }

    fun clearRuntimeLogs() {
        ProxyRuntimeState.clearLogs()
    }

    private fun updateSettings(transform: (ProxySettings) -> ProxySettings) {
        viewModelScope.launch {
            settingsRepository.save(transform(settings.value))
        }
    }
}
