package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.AtomicFile
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object CrashDiagnostics {
    private val mutableLastReport = MutableStateFlow<String?>(null)
    val lastReport: StateFlow<String?> = mutableLastReport.asStateFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialized = AtomicBoolean(false)
    private val reportMutationGeneration = AtomicLong(0)
    private val breadcrumbLock = Any()
    private val lifecycleBreadcrumbs = ArrayDeque<String>()

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var reportStore: AtomicTextFile? = null

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        applicationContext = appContext
        val diagnosticsDirectory = File(appContext.noBackupFilesDir, DIAGNOSTICS_DIRECTORY)
        runCatching { diagnosticsDirectory.mkdirs() }

        val store = AtomicTextFile(
            file = File(diagnosticsDirectory, LAST_CRASH_FILE),
            maxBytes = MAX_REPORT_BYTES,
        )
        reportStore = store
        mutableLastReport.value = runCatching { store.read() }.getOrNull()

        val environment = runCatching { readEnvironment(appContext) }
            .getOrElse { minimalEnvironment(appContext) }

        installUncaughtExceptionHandler(environment, store)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val installMarkerStore = AtomicTextFile(
                file = File(diagnosticsDirectory, INSTALL_MARKER_FILE),
                maxBytes = 64,
            )
            val installedAtMs = runCatching { installMarkerStore.read() }
                .getOrNull()
                ?.trim()
                ?.toLongOrNull()
                ?: System.currentTimeMillis().also { timestamp ->
                    runCatching { installMarkerStore.write(timestamp.toString()) }
                }
            val captureGeneration = reportMutationGeneration.get()
            ioScope.launch {
                capturePreviousProcessExit(
                    context = appContext,
                    diagnosticsDirectory = diagnosticsDirectory,
                    store = store,
                    environment = environment,
                    installedAtMs = installedAtMs,
                    captureGeneration = captureGeneration,
                )
            }
        }
    }

    fun recordLifecycle(message: String) {
        val sanitized = CrashReportFormatter.sanitize(message).replace('\n', ' ').take(MAX_BREADCRUMB_CHARS)
        val breadcrumb = "${System.currentTimeMillis()} $sanitized"
        synchronized(breadcrumbLock) {
            lifecycleBreadcrumbs.addLast(breadcrumb)
            while (lifecycleBreadcrumbs.size > MAX_BREADCRUMBS) {
                lifecycleBreadcrumbs.removeFirst()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val context = applicationContext ?: return
            runCatching {
                val manager = context.getSystemService(ActivityManager::class.java)
                val summary = "v1|$sanitized".toByteArray(StandardCharsets.UTF_8)
                manager?.setProcessStateSummary(summary.copyOf(minOf(summary.size, MAX_PROCESS_STATE_BYTES)))
            }
        }
    }

    fun clearLastReport() {
        reportMutationGeneration.incrementAndGet()
        val store = reportStore
        if (store == null) {
            mutableLastReport.value = null
            return
        }
        synchronized(store) {
            runCatching { store.delete() }
            mutableLastReport.value = null
        }
    }

    private fun installUncaughtExceptionHandler(
        environment: CrashReportEnvironment,
        store: AtomicTextFile,
    ) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val handler = PersistingUncaughtExceptionHandler(
            persist = { thread, throwable ->
                val timestampMs = System.currentTimeMillis()
                val report = runCatching {
                    CrashReportFormatter.formatUncaughtException(
                        environment = environment,
                        timestampMs = timestampMs,
                        threadName = thread.name,
                        throwable = throwable,
                        lifecycleBreadcrumbs = lifecycleBreadcrumbSnapshot(),
                        runtimeLogs = ProxyRuntimeState.state.value.logLines,
                    )
                }.getOrElse {
                    CrashReportFormatter.formatMinimalUncaughtException(
                        environment = environment,
                        timestampMs = timestampMs,
                        threadName = thread.name,
                        throwable = throwable,
                    )
                }
                synchronized(store) {
                    store.write(report)
                    mutableLastReport.value = report
                }
            },
            delegate = previousHandler,
            terminateProcess = {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            },
        )
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun capturePreviousProcessExit(
        context: Context,
        diagnosticsDirectory: File,
        store: AtomicTextFile,
        environment: CrashReportEnvironment,
        installedAtMs: Long,
        captureGeneration: Long,
    ) {
        runCatching {
            val cursorStore = AtomicTextFile(
                file = File(diagnosticsDirectory, EXIT_CURSOR_FILE),
                maxBytes = 64,
            )
            val persistedCursor = cursorStore.read()?.trim()?.toLongOrNull()
            val cursor = persistedCursor ?: installedAtMs
            val manager = context.getSystemService(ActivityManager::class.java) ?: return
            val records = manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_RECORDS)
            val newestTimestamp = records.maxOfOrNull { it.timestamp } ?: return
            val latestActionableExit = records
                .asSequence()
                .filter { exit ->
                    if (persistedCursor == null) {
                        exit.timestamp >= cursor
                    } else {
                        exit.timestamp > cursor
                    }
                }
                .filter { isActionableExitReason(it.reason) }
                .maxByOrNull { it.timestamp }

            if (latestActionableExit != null) {
                val report = CrashReportFormatter.formatProcessExit(
                    environment = environment,
                    exit = latestActionableExit.toSnapshot(),
                    lifecycleBreadcrumbs = emptyList(),
                )
                synchronized(store) {
                    val currentReport = store.read()
                    val currentTimestamp = CrashReportFormatter.eventTimestamp(currentReport)
                    if (captureGeneration == reportMutationGeneration.get() &&
                        HistoricalExitReportPolicy.shouldReplace(
                            existingTimestampMs = currentTimestamp,
                            candidateTimestampMs = latestActionableExit.timestamp,
                            candidateIsJavaCrash = latestActionableExit.reason == ApplicationExitInfo.REASON_CRASH,
                        )
                    ) {
                        store.write(report)
                        mutableLastReport.value = report
                    }
                }
            }

            cursorStore.write(newestTimestamp.toString())
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun ApplicationExitInfo.toSnapshot(): ProcessExitSnapshot {
        val trace = if (reason == ApplicationExitInfo.REASON_ANR) {
            runCatching {
                traceInputStream?.use { input -> readBoundedUtf8(input, MAX_TRACE_BYTES) }
            }.getOrNull()
        } else {
            null
        }
        val lifecycleSummary = processStateSummary
            ?.toString(StandardCharsets.UTF_8)
            ?.takeIf(String::isNotBlank)

        return ProcessExitSnapshot(
            timestampMs = timestamp,
            reason = exitReasonName(reason),
            description = description,
            status = status,
            importance = importance,
            processName = processName,
            pssKb = pss,
            rssKb = rss,
            lifecycleSummary = lifecycleSummary,
            trace = trace,
        )
    }

    private fun lifecycleBreadcrumbSnapshot(): List<String> {
        return synchronized(breadcrumbLock) { lifecycleBreadcrumbs.toList() }
    }

    private fun readEnvironment(context: Context): CrashReportEnvironment {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        return CrashReportEnvironment(
            packageName = context.packageName,
            versionName = packageInfo.versionName ?: "unknown",
            versionCode = versionCode,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
        )
    }

    private fun minimalEnvironment(context: Context): CrashReportEnvironment {
        return CrashReportEnvironment(
            packageName = context.packageName,
            versionName = "unknown",
            versionCode = 0,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun isActionableExitReason(reason: Int): Boolean {
        return reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
            reason == ApplicationExitInfo.REASON_CRASH ||
            reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            reason == ApplicationExitInfo.REASON_ANR ||
            reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE ||
            reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitReasonName(reason: Int): String {
        return when (reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            else -> "UNKNOWN($reason)"
        }
    }

    private fun readBoundedUtf8(input: java.io.InputStream, maxBytes: Int): String {
        val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
        val buffer = ByteArray(4 * 1024)
        while (output.size() < maxBytes) {
            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
            if (count <= 0) break
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private const val DIAGNOSTICS_DIRECTORY = "crash_diagnostics"
    private const val LAST_CRASH_FILE = "last_crash.txt"
    private const val EXIT_CURSOR_FILE = "last_exit_timestamp.txt"
    private const val INSTALL_MARKER_FILE = "diagnostics_installed_at.txt"
    private const val MAX_REPORT_BYTES = 96 * 1024
    private const val MAX_TRACE_BYTES = 64 * 1024
    private const val MAX_EXIT_RECORDS = 16
    private const val MAX_BREADCRUMBS = 32
    private const val MAX_BREADCRUMB_CHARS = 192
    private const val MAX_PROCESS_STATE_BYTES = 128
}

internal class AtomicTextFile(
    private val file: File,
    private val maxBytes: Int,
) {
    private val atomicFile = AtomicFile(file)

    @Synchronized
    fun read(): String? {
        return try {
            atomicFile.openRead().use { input ->
                val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
                val buffer = ByteArray(4 * 1024)
                while (output.size() < maxBytes) {
                    val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                }
                output.toString(StandardCharsets.UTF_8.name()).takeIf(String::isNotBlank)
            }
        } catch (_: FileNotFoundException) {
            null
        }
    }

    @Synchronized
    fun write(text: String) {
        runCatching { file.parentFile?.mkdirs() }
        val bytes = text.toByteArray(StandardCharsets.UTF_8).let { encoded ->
            if (encoded.size <= maxBytes) encoded else encoded.copyOf(maxBytes)
        }
        var output: java.io.FileOutputStream? = null
        try {
            val stream = atomicFile.startWrite()
            output = stream
            stream.write(bytes)
            stream.flush()
            atomicFile.finishWrite(stream)
            output = null
        } catch (throwable: Throwable) {
            output?.let(atomicFile::failWrite)
            throw throwable
        }
    }

    @Synchronized
    fun delete() {
        atomicFile.delete()
    }
}
