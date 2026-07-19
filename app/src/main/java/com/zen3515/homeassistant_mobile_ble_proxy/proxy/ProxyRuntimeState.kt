package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RuntimeScannerState {
    IDLE,
    STARTING,
    RUNNING,
    FAILED,
    STOPPING,
    STOPPED,
}

data class ProxyRuntimeSnapshot(
    val serviceRunning: Boolean = false,
    val scannerState: RuntimeScannerState = RuntimeScannerState.IDLE,
    val desiredScanProfile: ScanProfile? = null,
    val activeScanProfile: ScanProfile? = null,
    val clientCount: Int = 0,
    val advertisementsForwarded: Long = 0,
    val listeningPort: Int = 0,
    val lastError: String? = null,
    val logLines: List<String> = emptyList(),
)

object ProxyRuntimeState {
    private val mutableState = MutableStateFlow(ProxyRuntimeSnapshot())
    val state: StateFlow<ProxyRuntimeSnapshot> = mutableState.asStateFlow()

    fun update(transform: (ProxyRuntimeSnapshot) -> ProxyRuntimeSnapshot) {
        mutableState.update(transform)
    }

    fun setServiceRunning(running: Boolean, port: Int = 0) {
        update { current ->
            current.copy(
                serviceRunning = running,
                listeningPort = if (running) port else 0,
                lastError = if (running) null else current.lastError,
            )
        }
    }

    fun reconcileObservedServiceRunning(running: Boolean) {
        update { current ->
            if (current.serviceRunning == running) {
                current
            } else {
                current.copy(
                    serviceRunning = running,
                    listeningPort = if (running) current.listeningPort else 0,
                )
            }
        }
    }

    fun setScannerState(state: RuntimeScannerState) {
        update { it.copy(scannerState = state) }
    }

    fun setDesiredScanProfile(profile: ScanProfile?) {
        update { it.copy(desiredScanProfile = profile) }
    }

    fun setActiveScanProfile(profile: ScanProfile?) {
        update { it.copy(activeScanProfile = profile) }
    }

    fun setClientCount(count: Int) {
        update { it.copy(clientCount = count.coerceAtLeast(0)) }
    }

    fun incrementAdvertisementsForwarded(by: Int = 1) {
        if (by <= 0) return
        update { it.copy(advertisementsForwarded = it.advertisementsForwarded + by) }
    }

    fun setError(message: String?) {
        update { it.copy(lastError = message) }
    }

    fun appendLog(message: String) {
        val text = message.trim()
        if (text.isEmpty()) {
            return
        }

        val timestamp = timestampFormatter().format(Date())
        val line = "$timestamp $text"
        update { current ->
            current.copy(logLines = (current.logLines + line).takeLast(MAX_LOG_LINES))
        }
    }

    fun clearLogs() {
        update { it.copy(logLines = emptyList()) }
    }

    fun resetCounters() {
        update {
            it.copy(
                advertisementsForwarded = 0,
                clientCount = 0,
                scannerState = RuntimeScannerState.IDLE,
                desiredScanProfile = null,
                activeScanProfile = null,
                lastError = null,
            )
        }
    }

    private fun timestampFormatter(): SimpleDateFormat {
        return SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    private const val MAX_LOG_LINES = 5_000
}
