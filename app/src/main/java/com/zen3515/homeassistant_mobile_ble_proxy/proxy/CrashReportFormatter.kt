package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

internal data class CrashReportEnvironment(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
)

internal data class ProcessExitSnapshot(
    val timestampMs: Long,
    val reason: String,
    val description: String?,
    val status: Int,
    val importance: Int,
    val processName: String,
    val pssKb: Long,
    val rssKb: Long,
    val lifecycleSummary: String?,
    val trace: String?,
)

internal object CrashReportFormatter {
    const val MAX_REPORT_CHARS = 96 * 1024
    private const val MAX_STACK_CHARS = 64 * 1024
    private const val MAX_RUNTIME_LOG_LINES = 100
    private const val TRUNCATED_MARKER = "\n...[report truncated]"
    private const val SECTION_TRUNCATED_MARKER = "\n...[section truncated]"

    private val macAddressPattern = Regex("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b")
    private val ipv4Pattern = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    private val compressedIpv6Pattern = Regex(
        "(?i)(?<![0-9a-f:])(?:[0-9a-f]{0,4}:){1,7}:[0-9a-f]{0,4}(?![0-9a-f:])",
    )
    private val expandedIpv6Pattern = Regex(
        "(?i)(?<![0-9a-f:])(?:[0-9a-f]{1,4}:){4,7}[0-9a-f]{1,4}(?![0-9a-f:])",
    )
    private val secretAssignmentPattern = Regex(
        "(?i)\\b(key|token|password|secret|psk)\\b[\\\"']?\\s*[:=]\\s*[\\\"']?[^\\\"'\\s,;}]+[\\\"']?",
    )
    private val base64SecretPattern = Regex(
        "(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{42,44}={0,2}(?![A-Za-z0-9+/=])",
    )

    fun formatUncaughtException(
        environment: CrashReportEnvironment,
        timestampMs: Long,
        threadName: String,
        throwable: Throwable,
        lifecycleBreadcrumbs: List<String>,
        runtimeLogs: List<String>,
    ): String {
        val stackTrace = runCatching {
            StringWriter().use { writer ->
                PrintWriter(writer).use { printWriter ->
                    throwable.printStackTrace(printWriter)
                }
                writer.toString()
            }
        }.getOrElse {
            "${throwable.javaClass.name}: stack trace unavailable"
        }.let { truncateSection(it, MAX_STACK_CHARS) }

        return boundedAndSanitized(
            buildString {
                appendHeader(
                    source = "uncaught Java/Kotlin exception",
                    timestampMs = timestampMs,
                    environment = environment,
                )
                appendLine("Thread: $threadName")
                appendLine("Exception: ${throwable.javaClass.name}")
                appendLine("Message: ${throwable.message ?: "<none>"}")
                appendLifecycle(lifecycleBreadcrumbs)
                appendLine()
                appendLine("Stack trace")
                appendLine("-----------")
                appendLine(stackTrace)
                appendRuntimeLogs(runtimeLogs)
            },
        )
    }

    fun formatProcessExit(
        environment: CrashReportEnvironment,
        exit: ProcessExitSnapshot,
        lifecycleBreadcrumbs: List<String>,
    ): String {
        return boundedAndSanitized(
            buildString {
                appendHeader(
                    source = "Android historical process exit",
                    timestampMs = exit.timestampMs,
                    environment = environment,
                )
                appendLine("Reason: ${exit.reason}")
                appendLine("Description: ${exit.description ?: "<none>"}")
                appendLine("Status: ${exit.status}")
                appendLine("Importance: ${exit.importance}")
                appendLine("Process: ${exit.processName}")
                appendLine("Last sampled PSS: ${exit.pssKb} KiB")
                appendLine("Last sampled RSS: ${exit.rssKb} KiB")
                exit.lifecycleSummary?.let { appendLine("Last lifecycle state: $it") }
                appendLifecycle(lifecycleBreadcrumbs)
                exit.trace?.let { trace ->
                    appendLine()
                    appendLine("System trace")
                    appendLine("------------")
                    appendLine(truncateSection(trace, MAX_STACK_CHARS))
                }
            },
        )
    }

    fun formatMinimalUncaughtException(
        environment: CrashReportEnvironment,
        timestampMs: Long,
        threadName: String,
        throwable: Throwable,
    ): String {
        return boundedAndSanitized(
            buildString {
                appendHeader(
                    source = "minimal uncaught Java/Kotlin exception",
                    timestampMs = timestampMs,
                    environment = environment,
                )
                appendLine("Thread: $threadName")
                appendLine("Exception: ${throwable.javaClass.name}")
                appendLine("Message: ${throwable.message ?: "<none>"}")
                throwable.stackTrace.take(MINIMAL_STACK_FRAMES).forEach { frame ->
                    appendLine("\tat $frame")
                }
            },
        )
    }

    fun eventTimestamp(report: String?): Long? {
        if (report == null) return null
        return report.lineSequence()
            .firstOrNull { it.startsWith("Event timestamp millis: ") }
            ?.substringAfter(": ")
            ?.toLongOrNull()
    }

    internal fun sanitize(text: String): String {
        return text
            .replace(secretAssignmentPattern) { match -> "${match.groupValues[1]}=[redacted]" }
            .replace(macAddressPattern, "[redacted-mac]")
            .replace(ipv4Pattern, "[redacted-ip]")
            .replace(compressedIpv6Pattern, "[redacted-ip]")
            .replace(expandedIpv6Pattern, "[redacted-ip]")
            .replace(base64SecretPattern, "[redacted-secret]")
    }

    private fun StringBuilder.appendHeader(
        source: String,
        timestampMs: Long,
        environment: CrashReportEnvironment,
    ) {
        appendLine("Home Assistant Mobile BLE Proxy crash report")
        appendLine("============================================")
        appendLine("Source: $source")
        appendLine("Occurred at: ${formatTimestamp(timestampMs)}")
        appendLine("Event timestamp millis: $timestampMs")
        appendLine(
            "App: ${environment.packageName} ${environment.versionName} " +
                "(${environment.versionCode})",
        )
        appendLine(
            "Device: ${environment.manufacturer} ${environment.model}; " +
                "Android ${environment.androidRelease} (SDK ${environment.sdkInt})",
        )
    }

    private fun StringBuilder.appendLifecycle(breadcrumbs: List<String>) {
        if (breadcrumbs.isEmpty()) return
        appendLine()
        appendLine("Lifecycle breadcrumbs")
        appendLine("---------------------")
        breadcrumbs.forEach(::appendLine)
    }

    private fun StringBuilder.appendRuntimeLogs(runtimeLogs: List<String>) {
        if (runtimeLogs.isEmpty()) return
        appendLine()
        appendLine("Recent runtime log (last ${minOf(runtimeLogs.size, MAX_RUNTIME_LOG_LINES)} lines)")
        appendLine("------------------------------------------------")
        runtimeLogs.takeLast(MAX_RUNTIME_LOG_LINES).forEach(::appendLine)
    }

    private fun boundedAndSanitized(report: String): String {
        val sanitized = sanitize(report)
        if (sanitized.length <= MAX_REPORT_CHARS) return sanitized
        return sanitized.take(MAX_REPORT_CHARS - TRUNCATED_MARKER.length) + TRUNCATED_MARKER
    }

    private fun truncateSection(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars - SECTION_TRUNCATED_MARKER.length) + SECTION_TRUNCATED_MARKER
    }

    private fun formatTimestamp(timestampMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(timestampMs))
    }

    private const val MINIMAL_STACK_FRAMES = 32
}

internal object HistoricalExitReportPolicy {
    fun shouldReplace(
        existingTimestampMs: Long?,
        candidateTimestampMs: Long,
        candidateIsJavaCrash: Boolean,
    ): Boolean {
        if (existingTimestampMs == null) return true
        if (candidateIsJavaCrash &&
            abs(existingTimestampMs - candidateTimestampMs) <= SAME_JAVA_CRASH_WINDOW_MS
        ) {
            return false
        }
        return candidateTimestampMs > existingTimestampMs
    }

    private const val SAME_JAVA_CRASH_WINDOW_MS = 30_000L
}

internal class PersistingUncaughtExceptionHandler(
    private val persist: (Thread, Throwable) -> Unit,
    private val delegate: Thread.UncaughtExceptionHandler?,
    private val terminateProcess: () -> Unit,
) : Thread.UncaughtExceptionHandler {
    private val handling = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        if (handling.compareAndSet(false, true)) {
            try {
                persist(thread, throwable)
            } catch (_: Throwable) {
                // Diagnostics must never mask or replace the original crash.
            }
        }

        val originalHandler = delegate
        if (originalHandler != null && originalHandler !== this) {
            originalHandler.uncaughtException(thread, throwable)
        } else {
            terminateProcess()
        }
    }
}
