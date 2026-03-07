package com.zen3515.homeassistant_mobile_ble_proxy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.zen3515.homeassistant_mobile_ble_proxy.proxy.AdvertisementFilterRule
import com.zen3515.homeassistant_mobile_ble_proxy.proxy.LockScreenScanTarget
import com.zen3515.homeassistant_mobile_ble_proxy.proxy.MainUiState
import com.zen3515.homeassistant_mobile_ble_proxy.proxy.MainViewModel
import com.zen3515.homeassistant_mobile_ble_proxy.proxy.NsdInterfaceMode
import com.zen3515.homeassistant_mobile_ble_proxy.proxy.ProxyIdentity
import com.zen3515.homeassistant_mobile_ble_proxy.proxy.ProxyRuntimeSnapshot
import com.zen3515.homeassistant_mobile_ble_proxy.proxy.ProxySettings
import com.zen3515.homeassistant_mobile_ble_proxy.proxy.ScannerMode
import com.zen3515.homeassistant_mobile_ble_proxy.ui.theme.HaMobileBleProxyTheme
import java.util.UUID
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HaMobileBleProxyTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                ProxyScreen(
                    uiState = uiState,
                    onProxyEnabled = viewModel::setProxyEnabled,
                    onAutoStartOnBootChange = viewModel::setAutoStartOnBoot,
                    onSaveSettings = viewModel::saveSettings,
                    onClearRuntimeLogs = viewModel::clearRuntimeLogs,
                )
            }
        }
    }
}

private enum class ProxyPage {
    HOME,
    SETTINGS,
    ADVERTISEMENT_FILTERS,
    LOCK_SCREEN_TARGETS,
}

@Composable
private fun ProxyScreen(
    uiState: MainUiState,
    onProxyEnabled: (Boolean) -> Unit,
    onAutoStartOnBootChange: (Boolean) -> Unit,
    onSaveSettings: (ProxySettings) -> Unit,
    onClearRuntimeLogs: () -> Unit,
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.isEmpty()) {
            return@rememberLauncherForActivityResult
        }

        val deniedPermissions = result.filterValues { granted -> !granted }.keys
        if (deniedPermissions.isEmpty()) {
            Toast.makeText(context, "All requested permissions granted.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                context,
                "Some permissions were denied. Open app permission settings if needed.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    var currentPage by rememberSaveable { mutableStateOf(ProxyPage.HOME) }
    var logWrapEnabled by rememberSaveable { mutableStateOf(false) }
    var settingsBaseline by remember { mutableStateOf(uiState.settings) }
    var settingsDraft by remember { mutableStateOf(uiState.settings) }
    var apiPortInput by remember { mutableStateOf(uiState.settings.apiPort.toString()) }
    var flushIntervalInput by remember { mutableStateOf(uiState.settings.advertisementFlushIntervalMs.toString()) }
    var dedupWindowInput by remember { mutableStateOf(uiState.settings.advertisementDedupWindowMs.toString()) }
    var discoveryThrottleIntervalInput by remember {
        mutableStateOf(uiState.settings.advertisementDiscoveryThrottleIntervalMs.toString())
    }
    var watchdogIntervalInput by remember { mutableStateOf(uiState.settings.scannerHealthCheckIntervalMs.toString()) }
    var lowRateChecksInput by remember { mutableStateOf(uiState.settings.scannerLowRateConsecutiveChecks.toString()) }

    val openSettings = {
        settingsBaseline = uiState.settings
        settingsDraft = uiState.settings
        apiPortInput = uiState.settings.apiPort.toString()
        flushIntervalInput = uiState.settings.advertisementFlushIntervalMs.toString()
        dedupWindowInput = uiState.settings.advertisementDedupWindowMs.toString()
        discoveryThrottleIntervalInput = uiState.settings.advertisementDiscoveryThrottleIntervalMs.toString()
        watchdogIntervalInput = uiState.settings.scannerHealthCheckIntervalMs.toString()
        lowRateChecksInput = uiState.settings.scannerLowRateConsecutiveChecks.toString()
        currentPage = ProxyPage.SETTINGS
    }

    when (currentPage) {
        ProxyPage.HOME -> {
            HomeScreen(
                uiState = uiState,
                onProxyEnabled = onProxyEnabled,
                onAutoStartOnBootChange = onAutoStartOnBootChange,
                batteryOptimizationIgnored = isBatteryOptimizationIgnored(context),
                onRequestPermissions = {
                    val missing = missingRuntimePermissions(context)
                    if (missing.isEmpty()) {
                        Toast.makeText(context, "BLE and notification permissions are already granted.", Toast.LENGTH_SHORT)
                            .show()
                    } else {
                        permissionLauncher.launch(missing.toTypedArray())
                    }
                },
                onRequestDisableBatteryOptimizations = {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                        Toast.makeText(context, "Battery optimization is not used on this Android version.", Toast.LENGTH_SHORT)
                            .show()
                    } else {
                        val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = "package:${context.packageName}".toUri()
                        }
                        runCatching {
                            context.startActivity(requestIntent)
                        }.onFailure {
                            val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            runCatching { context.startActivity(fallbackIntent) }
                        }
                    }
                },
                onOpenBatterySettings = {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    runCatching {
                        context.startActivity(intent)
                    }
                },
                onOpenAppPermissionSettings = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = "package:${context.packageName}".toUri()
                    }
                    runCatching {
                        context.startActivity(intent)
                    }
                },
                onOpenSettings = openSettings,
                onClearLogs = onClearRuntimeLogs,
                logWrapEnabled = logWrapEnabled,
                onToggleLogWrap = { logWrapEnabled = !logWrapEnabled },
            )
        }

        ProxyPage.SETTINGS -> {
            SettingsScreen(
                baselineSettings = settingsBaseline,
                draftSettings = settingsDraft,
                apiPortInput = apiPortInput,
                flushIntervalInput = flushIntervalInput,
                dedupWindowInput = dedupWindowInput,
                discoveryThrottleIntervalInput = discoveryThrottleIntervalInput,
                watchdogIntervalInput = watchdogIntervalInput,
                lowRateChecksInput = lowRateChecksInput,
                onDraftSettingsChange = { settingsDraft = it },
                onApiPortInputChange = { apiPortInput = it.filter(Char::isDigit).take(5) },
                onFlushIntervalInputChange = { flushIntervalInput = it.filter(Char::isDigit).take(5) },
                onDedupWindowInputChange = { dedupWindowInput = it.filter(Char::isDigit).take(5) },
                onDiscoveryThrottleIntervalInputChange = {
                    discoveryThrottleIntervalInput = it.filter(Char::isDigit).take(8)
                },
                onWatchdogIntervalInputChange = {
                    watchdogIntervalInput = it.filter(Char::isDigit).take(6)
                },
                onLowRateChecksInputChange = {
                    lowRateChecksInput = it.filter(Char::isDigit).take(2)
                },
                onOpenFilters = { currentPage = ProxyPage.ADVERTISEMENT_FILTERS },
                onOpenLockScreenTargets = { currentPage = ProxyPage.LOCK_SCREEN_TARGETS },
                onBack = { currentPage = ProxyPage.HOME },
                onSave = {
                    val parsedPort = apiPortInput.toIntOrNull() ?: settingsDraft.apiPort
                    val parsedFlushInterval = flushIntervalInput.toIntOrNull() ?: settingsDraft.advertisementFlushIntervalMs
                    val parsedDedupWindow = dedupWindowInput.toIntOrNull() ?: settingsDraft.advertisementDedupWindowMs
                    val parsedDiscoveryThrottleInterval = discoveryThrottleIntervalInput.toIntOrNull() ?:
                        settingsDraft.advertisementDiscoveryThrottleIntervalMs
                    val parsedWatchdogInterval = watchdogIntervalInput.toIntOrNull() ?:
                        settingsDraft.scannerHealthCheckIntervalMs
                    val parsedLowRateChecks = lowRateChecksInput.toIntOrNull() ?:
                        settingsDraft.scannerLowRateConsecutiveChecks
                    onSaveSettings(
                        settingsDraft.copy(
                            apiPort = parsedPort,
                            advertisementFlushIntervalMs = parsedFlushInterval,
                            advertisementDedupWindowMs = parsedDedupWindow,
                            advertisementDiscoveryThrottleIntervalMs = parsedDiscoveryThrottleInterval,
                            scannerHealthCheckIntervalMs = parsedWatchdogInterval,
                            scannerLowRateConsecutiveChecks = parsedLowRateChecks,
                        ),
                    )
                    currentPage = ProxyPage.HOME
                },
            )
        }

        ProxyPage.ADVERTISEMENT_FILTERS -> {
            AdvertisementFiltersScreen(
                rules = settingsDraft.advertisementFilters,
                onRulesChange = { rules ->
                    settingsDraft = settingsDraft.copy(advertisementFilters = rules)
                },
                onBack = { currentPage = ProxyPage.SETTINGS },
            )
        }

        ProxyPage.LOCK_SCREEN_TARGETS -> {
            LockScreenTargetsScreen(
                autoAddMatchedDevices = settingsDraft.autoAddMatchedDevicesToLockScreenTargets,
                advertisementFilters = settingsDraft.advertisementFilters,
                targets = settingsDraft.lockScreenScanTargets,
                onAutoAddMatchedDevicesChange = { enabled ->
                    settingsDraft = settingsDraft.copy(autoAddMatchedDevicesToLockScreenTargets = enabled)
                },
                onTargetsChange = { targets ->
                    settingsDraft = settingsDraft.copy(lockScreenScanTargets = targets)
                },
                onBack = { currentPage = ProxyPage.SETTINGS },
            )
        }
    }
}

@Composable
private fun HomeScreen(
    uiState: MainUiState,
    onProxyEnabled: (Boolean) -> Unit,
    onAutoStartOnBootChange: (Boolean) -> Unit,
    batteryOptimizationIgnored: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestDisableBatteryOptimizations: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAppPermissionSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onClearLogs: () -> Unit,
    logWrapEnabled: Boolean,
    onToggleLogWrap: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("ESPHome BLE Proxy", style = MaterialTheme.typography.titleMedium)
                        Switch(
                            checked = uiState.runtime.serviceRunning,
                            onCheckedChange = onProxyEnabled,
                        )
                    }

                    Text(
                        if (uiState.runtime.serviceRunning) {
                            "Running on port ${uiState.runtime.listeningPort}"
                        } else {
                            "Stopped"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("Clients: ${uiState.runtime.clientCount}")
                    Text("Scanner: ${uiState.runtime.scannerState.name.lowercase()}")
                    Text("Advertisements forwarded: ${uiState.runtime.advertisementsForwarded}")
                    uiState.runtime.lastError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val allLogs = uiState.runtime.logLines
                    val visibleLogs = allLogs.takeLast(2_000)
                    val logText = visibleLogs.joinToString("\n")
                    val copyText = allLogs.joinToString("\n")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Runtime Log", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onToggleLogWrap) {
                                Text("Wrap: ${if (logWrapEnabled) "On" else "Off"}")
                            }
                            TextButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(copyText))
                                },
                                enabled = allLogs.isNotEmpty(),
                            ) {
                                Text("Copy Log")
                            }
                            TextButton(
                                onClick = onClearLogs,
                                enabled = allLogs.isNotEmpty(),
                            ) {
                                Text("Clear Log")
                            }
                        }
                    }
                    if (allLogs.isEmpty()) {
                        Text("No logs yet.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            "Showing ${visibleLogs.size} of ${allLogs.size} lines",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        SelectionContainer {
                            Text(
                                text = logText,
                                modifier = if (logWrapEnabled) {
                                    Modifier.fillMaxWidth()
                                } else {
                                    Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                },
                                softWrap = logWrapEnabled,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Background & Permissions", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (batteryOptimizationIgnored) {
                            "Battery mode: unrestricted for this app."
                        } else {
                            "Battery mode: optimized. This can stop scanning on lock screen."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (batteryOptimizationIgnored) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Button(
                        onClick = onRequestPermissions,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Grant BLE/Location/Notification Permissions")
                    }
                    Button(
                        onClick = onRequestDisableBatteryOptimizations,
                        enabled = !batteryOptimizationIgnored,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (batteryOptimizationIgnored) {
                                "Battery Already Unrestricted"
                            } else {
                                "Request Unrestricted Battery"
                            },
                        )
                    }
                    Button(
                        onClick = onOpenBatterySettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open Battery Optimization Settings")
                    }
                    Button(
                        onClick = onOpenAppPermissionSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open App Permission Settings")
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Quick Settings", style = MaterialTheme.typography.titleMedium)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Auto start on boot")
                        Switch(
                            checked = uiState.settings.autoStartOnBoot,
                            onCheckedChange = onAutoStartOnBootChange,
                        )
                    }

                    Text("Node: ${uiState.settings.nodeName}")
                    Text("Port: ${uiState.settings.apiPort}")
                    Text("Scanner mode: ${uiState.settings.scannerMode.name.lowercase()}")
                    Text("mDNS interface: ${uiState.settings.nsdInterfaceMode.toDisplayLabel()}")
                    Text("Ad flush interval: ${uiState.settings.advertisementFlushIntervalMs} ms")
                    Text(
                        if (uiState.settings.advertisementDedupWindowMs > 0) {
                            "Ad dedup window: ${uiState.settings.advertisementDedupWindowMs} ms"
                        } else {
                            "Ad dedup window: disabled"
                        },
                    )
                    Text(
                        if (uiState.settings.advertisementDiscoveryThrottleIntervalMs > 0) {
                            "Discovery throttle: ${uiState.settings.advertisementDiscoveryThrottleIntervalMs} ms"
                        } else {
                            "Discovery throttle: disabled"
                        },
                    )
                    Text("Watchdog check interval: ${uiState.settings.scannerHealthCheckIntervalMs} ms")
                    Text("Low-rate restart checks: ${uiState.settings.scannerLowRateConsecutiveChecks}")
                    val enabledFilterCount = uiState.settings.advertisementFilters.count { it.enabled }
                    Text(
                        if (enabledFilterCount == 0) {
                            "Ad filters: allow all"
                        } else {
                            "Ad filters: $enabledFilterCount enabled (OR matching)"
                        },
                    )
                    val lockScreenTargetCount = uiState.settings.lockScreenScanTargets.size
                    Text(
                        if (lockScreenTargetCount == 0) {
                            "Lock-screen scan targets: none"
                        } else {
                            "Lock-screen scan targets: $lockScreenTargetCount"
                        },
                    )
                    Text(
                        if (uiState.settings.autoAddMatchedDevicesToLockScreenTargets) {
                            "Auto-add matched devices: on"
                        } else {
                            "Auto-add matched devices: off"
                        },
                    )

                    Button(
                        onClick = onOpenSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Configure Proxy Settings")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsScreen(
    baselineSettings: ProxySettings,
    draftSettings: ProxySettings,
    apiPortInput: String,
    flushIntervalInput: String,
    dedupWindowInput: String,
    discoveryThrottleIntervalInput: String,
    watchdogIntervalInput: String,
    lowRateChecksInput: String,
    onDraftSettingsChange: (ProxySettings) -> Unit,
    onApiPortInputChange: (String) -> Unit,
    onFlushIntervalInputChange: (String) -> Unit,
    onDedupWindowInputChange: (String) -> Unit,
    onDiscoveryThrottleIntervalInputChange: (String) -> Unit,
    onWatchdogIntervalInputChange: (String) -> Unit,
    onLowRateChecksInputChange: (String) -> Unit,
    onOpenFilters: () -> Unit,
    onOpenLockScreenTargets: () -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current

    var showEncryptionKey by rememberSaveable { mutableStateOf(false) }
    var showSaveConfirmation by rememberSaveable { mutableStateOf(false) }
    var showDiscardConfirmation by rememberSaveable { mutableStateOf(false) }

    val nodeNameValid = draftSettings.nodeName.any { it.isLetterOrDigit() || it == '_' || it == '-' }
    val normalizedMac = ProxyIdentity.normalizeMacAddress(draftSettings.bluetoothMacOverride)
    val macValid = draftSettings.bluetoothMacOverride.isBlank() || normalizedMac != null
    val portValue = apiPortInput.toIntOrNull()
    val portValid = portValue != null && portValue in 1024..65535
    val flushIntervalValue = flushIntervalInput.toIntOrNull()
    val flushIntervalValid = flushIntervalValue != null && flushIntervalValue in 50..10_000
    val dedupWindowValue = dedupWindowInput.toIntOrNull()
    val dedupWindowValid = dedupWindowValue != null && dedupWindowValue in 0..60_000
    val discoveryThrottleIntervalValue = discoveryThrottleIntervalInput.toIntOrNull()
    val discoveryThrottleIntervalValid = discoveryThrottleIntervalValue != null &&
        discoveryThrottleIntervalValue in 0..3_600_000
    val watchdogIntervalValue = watchdogIntervalInput.toIntOrNull()
    val watchdogIntervalValid = watchdogIntervalValue != null && watchdogIntervalValue in 5_000..120_000
    val lowRateChecksValue = lowRateChecksInput.toIntOrNull()
    val lowRateChecksValid = lowRateChecksValue != null && lowRateChecksValue in 1..12
    val encryptionKeyState = parseNoiseKey(draftSettings.espHomeApiEncryptionKey)
    val filterRegexValid = draftSettings.advertisementFilters.all {
        isRegexPatternValid(it.macRegex) && isRegexPatternValid(it.nameRegex)
    }
    val lockScreenTargetsValid = draftSettings.lockScreenScanTargets.all(::isLockScreenScanTargetValid)

    val hasChanges = draftSettings != baselineSettings ||
        apiPortInput != baselineSettings.apiPort.toString() ||
        flushIntervalInput != baselineSettings.advertisementFlushIntervalMs.toString() ||
        dedupWindowInput != baselineSettings.advertisementDedupWindowMs.toString() ||
        discoveryThrottleIntervalInput != baselineSettings.advertisementDiscoveryThrottleIntervalMs.toString() ||
        watchdogIntervalInput != baselineSettings.scannerHealthCheckIntervalMs.toString() ||
        lowRateChecksInput != baselineSettings.scannerLowRateConsecutiveChecks.toString()
    val canSave = nodeNameValid &&
        macValid &&
        portValid &&
        flushIntervalValid &&
        dedupWindowValid &&
        discoveryThrottleIntervalValid &&
        watchdogIntervalValid &&
        lowRateChecksValid &&
        encryptionKeyState != NoiseKeyState.INVALID &&
        filterRegexValid &&
        lockScreenTargetsValid

    val requestBack = {
        if (hasChanges) {
            showDiscardConfirmation = true
        } else {
            onBack()
        }
    }

    BackHandler(onBack = requestBack)

    if (showSaveConfirmation) {
        AlertDialog(
            onDismissRequest = { showSaveConfirmation = false },
            title = { Text("Save settings") },
            text = { Text("Apply all edited settings now?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSaveConfirmation = false
                        onSave()
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveConfirmation = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text("Discard changes") },
            text = { Text("You have unsaved changes. Discard them?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmation = false
                        onBack()
                    },
                ) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirmation = false }) {
                    Text("Keep editing")
                }
            },
        )
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Proxy Settings", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = requestBack) {
                                Text("Back")
                            }
                            Button(
                                onClick = { showSaveConfirmation = true },
                                enabled = canSave && hasChanges,
                            ) {
                                Text("Save")
                            }
                        }
                    }

                    OutlinedTextField(
                        value = draftSettings.nodeName,
                        onValueChange = { onDraftSettingsChange(draftSettings.copy(nodeName = it)) },
                        label = { Text("ESPHome node name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        isError = !nodeNameValid,
                    )

                    OutlinedTextField(
                        value = draftSettings.friendlyName,
                        onValueChange = { onDraftSettingsChange(draftSettings.copy(friendlyName = it)) },
                        label = { Text("Friendly name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = apiPortInput,
                        onValueChange = onApiPortInputChange,
                        label = { Text("API port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        isError = !portValid,
                    )

                    OutlinedTextField(
                        value = flushIntervalInput,
                        onValueChange = onFlushIntervalInputChange,
                        label = { Text("Ad flush interval (ms)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        isError = !flushIntervalValid,
                    )

                    OutlinedTextField(
                        value = dedupWindowInput,
                        onValueChange = onDedupWindowInputChange,
                        label = { Text("Ad dedup window (ms)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        isError = !dedupWindowValid,
                    )

                    OutlinedTextField(
                        value = discoveryThrottleIntervalInput,
                        onValueChange = onDiscoveryThrottleIntervalInputChange,
                        label = { Text("Discovery throttle interval (ms)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        isError = !discoveryThrottleIntervalValid,
                    )

                    OutlinedTextField(
                        value = watchdogIntervalInput,
                        onValueChange = onWatchdogIntervalInputChange,
                        label = { Text("Watchdog check interval (ms)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        isError = !watchdogIntervalValid,
                    )

                    OutlinedTextField(
                        value = lowRateChecksInput,
                        onValueChange = onLowRateChecksInputChange,
                        label = { Text("Low-rate checks before restart") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        isError = !lowRateChecksValid,
                    )

                    val enabledFilterCount = draftSettings.advertisementFilters.count { it.enabled }
                    Button(
                        onClick = onOpenFilters,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (enabledFilterCount == 0) {
                                "Configure Advertisement Filters (allow all)"
                            } else {
                                "Configure Advertisement Filters ($enabledFilterCount enabled)"
                            },
                        )
                    }

                    val lockScreenTargetCount = draftSettings.lockScreenScanTargets.size
                    Button(
                        onClick = onOpenLockScreenTargets,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (lockScreenTargetCount == 0) {
                                "Manage Lock-Screen Scan Targets"
                            } else {
                                "Manage Lock-Screen Scan Targets ($lockScreenTargetCount)"
                            },
                        )
                    }

                    OutlinedTextField(
                        value = draftSettings.bluetoothMacOverride,
                        onValueChange = { onDraftSettingsChange(draftSettings.copy(bluetoothMacOverride = it.trim())) },
                        label = { Text("Bluetooth MAC address") },
                        placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        isError = !macValid,
                    )

                    OutlinedTextField(
                        value = draftSettings.espHomeApiEncryptionKey,
                        onValueChange = { onDraftSettingsChange(draftSettings.copy(espHomeApiEncryptionKey = it.trim())) },
                        label = { Text("ESPHome API encryption key") },
                        singleLine = true,
                        visualTransformation = if (showEncryptionKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showEncryptionKey = !showEncryptionKey }) {
                                Icon(
                                    imageVector = if (showEncryptionKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showEncryptionKey) "Hide key" else "Show key",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        isError = encryptionKeyState == NoiseKeyState.INVALID,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(draftSettings.espHomeApiEncryptionKey))
                            },
                            enabled = draftSettings.espHomeApiEncryptionKey.isNotBlank(),
                        ) {
                            Text("Copy key")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Scanner mode")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    onDraftSettingsChange(draftSettings.copy(scannerMode = ScannerMode.PASSIVE))
                                },
                            ) {
                                Text(if (draftSettings.scannerMode == ScannerMode.PASSIVE) "Passive *" else "Passive")
                            }
                            Button(
                                onClick = {
                                    onDraftSettingsChange(draftSettings.copy(scannerMode = ScannerMode.ACTIVE))
                                },
                            ) {
                                Text(if (draftSettings.scannerMode == ScannerMode.ACTIVE) "Active *" else "Active")
                            }
                        }
                    }

                    Text("mDNS advertise interface")
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = {
                                    onDraftSettingsChange(draftSettings.copy(nsdInterfaceMode = NsdInterfaceMode.AUTO))
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (draftSettings.nsdInterfaceMode == NsdInterfaceMode.AUTO) "Auto *" else "Auto")
                            }
                            Button(
                                onClick = {
                                    onDraftSettingsChange(draftSettings.copy(nsdInterfaceMode = NsdInterfaceMode.WIFI))
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (draftSettings.nsdInterfaceMode == NsdInterfaceMode.WIFI) "Wi-Fi *" else "Wi-Fi")
                            }
                            Button(
                                onClick = {
                                    onDraftSettingsChange(draftSettings.copy(nsdInterfaceMode = NsdInterfaceMode.VPN))
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (draftSettings.nsdInterfaceMode == NsdInterfaceMode.VPN) "VPN *" else "VPN")
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = {
                                    onDraftSettingsChange(draftSettings.copy(nsdInterfaceMode = NsdInterfaceMode.CELLULAR))
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    if (draftSettings.nsdInterfaceMode == NsdInterfaceMode.CELLULAR) {
                                        "Cellular *"
                                    } else {
                                        "Cellular"
                                    },
                                )
                            }
                            Button(
                                onClick = {
                                    onDraftSettingsChange(draftSettings.copy(nsdInterfaceMode = NsdInterfaceMode.DISABLED))
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    if (draftSettings.nsdInterfaceMode == NsdInterfaceMode.DISABLED) {
                                        "Disabled *"
                                    } else {
                                        "Disabled"
                                    },
                                )
                            }
                        }
                    }

                    Text(
                        text = "Changes are saved in one batch after confirmation.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "Node name allows letters, digits, underscore, and hyphen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (nodeNameValid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = if (flushIntervalValid) {
                            "Ad flush interval controls send pacing (50-10000 ms)."
                        } else {
                            "Ad flush interval must be between 50 and 10000 ms."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (flushIntervalValid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = if (dedupWindowValid) {
                            "Ad dedup window applies to discovery traffic and limits to roughly one forwarded advertisement per device address within the window (0-60000 ms, 0 disables). Active GATT connection addresses bypass this dedup."
                        } else {
                            "Ad dedup window must be between 0 and 60000 ms."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (dedupWindowValid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = if (discoveryThrottleIntervalValid) {
                            "Discovery throttle limits forwarding frequency per device address (0-3600000 ms, 0 disables). Devices unseen for at least 30 minutes are treated as rediscovered and forwarded immediately."
                        } else {
                            "Discovery throttle interval must be between 0 and 3600000 ms."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (discoveryThrottleIntervalValid) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Text(
                        text = if (watchdogIntervalValid) {
                            "Watchdog checks scanner health every 5000-120000 ms (default 10000 ms)."
                        } else {
                            "Watchdog check interval must be between 5000 and 120000 ms."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (watchdogIntervalValid) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Text(
                        text = if (lowRateChecksValid) {
                            "Low-rate checks controls how many consecutive low-throughput checks trigger scanner restart (1-12, default 3)."
                        } else {
                            "Low-rate checks must be between 1 and 12."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (lowRateChecksValid) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Text(
                        text = when (draftSettings.nsdInterfaceMode) {
                            NsdInterfaceMode.AUTO -> {
                                "mDNS interface mode auto: Android chooses the active local network for NSD."
                            }

                            NsdInterfaceMode.WIFI -> {
                                "mDNS interface mode Wi-Fi: NSD is restricted to a Wi-Fi network when available."
                            }

                            NsdInterfaceMode.CELLULAR -> {
                                "mDNS interface mode cellular: NSD is restricted to cellular transport when available."
                            }

                            NsdInterfaceMode.VPN -> {
                                "mDNS interface mode VPN: NSD is restricted to VPN transport when available."
                            }

                            NsdInterfaceMode.DISABLED -> {
                                "mDNS interface mode disabled: no NSD advertisement is published."
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = if (enabledFilterCount == 0) {
                            "Advertisement filters: none enabled, so all advertisements are forwarded."
                        } else {
                            "Advertisement filters use OR logic across entries: an advertisement is forwarded if any enabled entry matches."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = if (macValid) {
                            if (normalizedMac != null) {
                                "Using MAC: $normalizedMac"
                            } else {
                                "Empty MAC uses stable generated default."
                            }
                        } else {
                            "Invalid MAC format. Use 12 hex bytes (for example AA:BB:CC:DD:EE:FF)."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (macValid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = when (encryptionKeyState) {
                            NoiseKeyState.EMPTY -> "Empty encryption key disables Noise encryption."
                            NoiseKeyState.VALID -> "Valid key. Proxy requires Noise NNpsk0 when saved."
                            NoiseKeyState.INVALID -> "Invalid key. Use a base64 value that decodes to 32 bytes."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (encryptionKeyState == NoiseKeyState.INVALID) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        text = if (filterRegexValid) {
                            "Advertisement filter regex patterns are valid."
                        } else {
                            "Advertisement filter list contains invalid regex; fix it before saving."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (filterRegexValid) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Text(
                        text = if (draftSettings.lockScreenScanTargets.isEmpty()) {
                            "Lock-screen scan targets: none configured. Android may stop broad BLE scanning while locked."
                        } else {
                            "Lock-screen scan targets: ${draftSettings.lockScreenScanTargets.size} saved. Exact MAC or exact device name entries are used for screen-off hardware filters."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (lockScreenTargetsValid) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Text(
                        text = if (draftSettings.autoAddMatchedDevicesToLockScreenTargets) {
                            "Auto-add matched devices is enabled. New exact MAC targets are learned only from advertisements that match at least one enabled advertisement filter."
                        } else {
                            "Auto-add matched devices is disabled."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AdvertisementFiltersScreen(
    rules: List<AdvertisementFilterRule>,
    onRulesChange: (List<AdvertisementFilterRule>) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Advertisement Filters", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onBack) {
                                Text("Back")
                            }
                            Button(
                                onClick = {
                                    onRulesChange(rules + newAdvertisementFilterRule())
                                },
                            ) {
                                Text("Add")
                            }
                        }
                    }

                    Text(
                        "If no enabled filter entries exist, all advertisements are forwarded.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Enabled entries use OR logic: an advertisement is forwarded if any entry matches MAC regex, name regex, and RSSI threshold within that entry.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (rules.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Text("No filter entries yet.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                rules.forEachIndexed { index, rule ->
                    AdvertisementFilterRuleCard(
                        index = index,
                        rule = rule,
                        onRuleChange = { updatedRule ->
                            onRulesChange(
                                rules.map { existing ->
                                    if (existing.id == updatedRule.id) {
                                        updatedRule
                                    } else {
                                        existing
                                    }
                                },
                            )
                        },
                        onRemove = {
                            onRulesChange(rules.filterNot { it.id == rule.id })
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LockScreenTargetsScreen(
    autoAddMatchedDevices: Boolean,
    advertisementFilters: List<AdvertisementFilterRule>,
    targets: List<LockScreenScanTarget>,
    onAutoAddMatchedDevicesChange: (Boolean) -> Unit,
    onTargetsChange: (List<LockScreenScanTarget>) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val enabledFilterCount = advertisementFilters.count { it.enabled }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Lock-Screen Scan Targets", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onBack) {
                                Text("Back")
                            }
                            Button(
                                onClick = {
                                    onTargetsChange(targets + newLockScreenScanTarget())
                                },
                            ) {
                                Text("Add")
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-add matched devices", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (enabledFilterCount == 0) {
                                    "No enabled advertisement filters exist, so auto-add is idle until at least one filter is enabled."
                                } else {
                                    "Matched advertisements add exact MAC targets for future lock-screen scanning."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (enabledFilterCount == 0) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                        Switch(
                            checked = autoAddMatchedDevices,
                            onCheckedChange = onAutoAddMatchedDevicesChange,
                        )
                    }

                    Text(
                        "These entries are exact lock-screen scan targets. When the screen turns off, Android-compatible hardware filters are built from exact MAC addresses and exact device names listed here.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (targets.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Text("No lock-screen scan targets yet.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                targets.forEachIndexed { index, target ->
                    LockScreenScanTargetCard(
                        index = index,
                        target = target,
                        onTargetChange = { updatedTarget ->
                            onTargetsChange(
                                targets.map { existing ->
                                    if (existing.id == updatedTarget.id) {
                                        updatedTarget
                                    } else {
                                        existing
                                    }
                                },
                            )
                        },
                        onRemove = {
                            onTargetsChange(targets.filterNot { it.id == target.id })
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LockScreenScanTargetCard(
    index: Int,
    target: LockScreenScanTarget,
    onTargetChange: (LockScreenScanTarget) -> Unit,
    onRemove: () -> Unit,
) {
    val normalizedMac = ProxyIdentity.normalizeMacAddress(target.macAddress)
    val macValid = target.macAddress.isBlank() || normalizedMac != null
    val targetValid = isLockScreenScanTargetValid(target)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Target ${index + 1}", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onRemove) {
                    Text("Remove")
                }
            }

            OutlinedTextField(
                value = target.macAddress,
                onValueChange = { value ->
                    onTargetChange(target.copy(macAddress = value.trim()))
                },
                label = { Text("Exact MAC address (optional)") },
                placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = !macValid,
            )

            OutlinedTextField(
                value = target.name,
                onValueChange = { value ->
                    onTargetChange(target.copy(name = value))
                },
                label = { Text("Exact device name (optional)") },
                placeholder = { Text("Sensor Beacon") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = when {
                    !macValid -> "Invalid MAC format. Use 12 hex bytes, for example AA:BB:CC:DD:EE:FF."
                    !targetValid -> "Enter at least one exact MAC address or exact device name."
                    normalizedMac != null -> "Hardware-eligible lock-screen filter using MAC $normalizedMac"
                    target.name.isNotBlank() -> "Hardware-eligible lock-screen filter using exact device name"
                    else -> "Not eligible for lock-screen filtering"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (macValid && targetValid) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

@Composable
private fun AdvertisementFilterRuleCard(
    index: Int,
    rule: AdvertisementFilterRule,
    onRuleChange: (AdvertisementFilterRule) -> Unit,
    onRemove: () -> Unit,
) {
    val macRegexValid = isRegexPatternValid(rule.macRegex)
    val nameRegexValid = isRegexPatternValid(rule.nameRegex)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Entry ${index + 1}", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRemove) {
                        Text("Remove")
                    }
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = { enabled ->
                            onRuleChange(rule.copy(enabled = enabled))
                        },
                    )
                }
            }

            OutlinedTextField(
                value = rule.macRegex,
                onValueChange = { value -> onRuleChange(rule.copy(macRegex = value)) },
                label = { Text("MAC regex (optional)") },
                placeholder = { Text("^AA:BB:CC:.*") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = !macRegexValid,
            )

            OutlinedTextField(
                value = rule.nameRegex,
                onValueChange = { value -> onRuleChange(rule.copy(nameRegex = value)) },
                label = { Text("Name regex (optional)") },
                placeholder = { Text("Sensor|Beacon") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = !nameRegexValid,
            )

            Text(
                text = if (rule.minRssi <= -127) {
                    "Minimum RSSI: disabled"
                } else {
                    "Minimum RSSI: ${rule.minRssi} dBm"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = rule.minRssi.toFloat(),
                onValueChange = { value ->
                    onRuleChange(rule.copy(minRssi = value.roundToInt().coerceIn(-127, 0)))
                },
                valueRange = -127f..0f,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!macRegexValid || !nameRegexValid) {
                Text(
                    text = "Fix invalid regex patterns before saving.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun requiredPermissions(): Array<String> {
    val permissions = mutableSetOf<String>()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        permissions += Manifest.permission.BLUETOOTH_SCAN
        permissions += Manifest.permission.BLUETOOTH_CONNECT
    }
    permissions += Manifest.permission.ACCESS_FINE_LOCATION
    if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.R) {
        permissions += Manifest.permission.ACCESS_BACKGROUND_LOCATION
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions += Manifest.permission.POST_NOTIFICATIONS
    }

    return permissions.toTypedArray()
}

private fun missingRuntimePermissions(context: Context): List<String> {
    return requiredPermissions().filter { permission ->
        ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
    }
}

private fun isBatteryOptimizationIgnored(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        return true
    }
    val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private enum class NoiseKeyState {
    EMPTY,
    VALID,
    INVALID,
}

private fun newAdvertisementFilterRule(): AdvertisementFilterRule {
    return AdvertisementFilterRule(
        id = UUID.randomUUID().toString(),
        enabled = true,
        macRegex = "",
        nameRegex = "",
        minRssi = -127,
    )
}

private fun newLockScreenScanTarget(): LockScreenScanTarget {
    return LockScreenScanTarget(
        id = UUID.randomUUID().toString(),
        macAddress = "",
        name = "",
    )
}

private fun isLockScreenScanTargetValid(target: LockScreenScanTarget): Boolean {
    val hasValidMac = target.macAddress.isBlank() || ProxyIdentity.normalizeMacAddress(target.macAddress) != null
    val hasAnySelector = target.macAddress.isNotBlank() || target.name.trim().isNotBlank()
    return hasValidMac && hasAnySelector
}

private fun isRegexPatternValid(pattern: String): Boolean {
    if (pattern.isBlank()) {
        return true
    }
    return runCatching { Regex(pattern) }.isSuccess
}

private fun parseNoiseKey(value: String): NoiseKeyState {
    if (value.isBlank()) {
        return NoiseKeyState.EMPTY
    }

    val normalized = value.filterNot(Char::isWhitespace)
    val decoded = runCatching { Base64.decode(normalized, Base64.DEFAULT) }.getOrNull() ?: return NoiseKeyState.INVALID

    return if (decoded.size == 32) NoiseKeyState.VALID else NoiseKeyState.INVALID
}

private fun NsdInterfaceMode.toDisplayLabel(): String {
    return when (this) {
        NsdInterfaceMode.AUTO -> "auto"
        NsdInterfaceMode.WIFI -> "Wi-Fi only"
        NsdInterfaceMode.CELLULAR -> "cellular only"
        NsdInterfaceMode.VPN -> "VPN only"
        NsdInterfaceMode.DISABLED -> "disabled"
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun ProxyScreenPreview() {
    HaMobileBleProxyTheme {
        ProxyScreen(
            uiState = MainUiState(
                settings = ProxySettings(
                    nodeName = "android_ble_proxy",
                    friendlyName = "Android BLE Proxy",
                    apiPort = 6053,
                    bluetoothMacOverride = "AA:BB:CC:DD:EE:FF",
                    espHomeApiEncryptionKey = "QRTIErOb/fcE9Ukd/5qA3RGYMn0Y+p06U58SCtOXvPc=",
                    autoStartOnBoot = true,
                    scannerMode = ScannerMode.PASSIVE,
                    advertisementFlushIntervalMs = 100,
                    advertisementDedupWindowMs = 10_000,
                    advertisementDiscoveryThrottleIntervalMs = 10_000,
                ),
                runtime = ProxyRuntimeSnapshot(
                    serviceRunning = true,
                    clientCount = 1,
                    advertisementsForwarded = 128,
                    listeningPort = 6053,
                    logLines = listOf(
                        "13:42:11.102 API server started (port=6053, encryption=on)",
                        "13:42:11.314 Client connected: 10.10.10.2",
                        "13:42:11.377 Client 10.10.10.2 subscribed BLE advertisements",
                        "13:42:11.378 BLE advertisement subscribers=1, starting scanner (passive)",
                        "13:42:11.411 Scanner state: running",
                    ),
                ),
            ),
            onProxyEnabled = {},
            onAutoStartOnBootChange = {},
            onSaveSettings = {},
            onClearRuntimeLogs = {},
        )
    }
}
