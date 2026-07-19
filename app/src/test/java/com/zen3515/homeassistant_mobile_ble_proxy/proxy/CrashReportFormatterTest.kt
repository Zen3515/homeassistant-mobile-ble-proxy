package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportFormatterTest {
    private val environment = CrashReportEnvironment(
        packageName = "com.example.proxy",
        versionName = "2026.8.0-dev-android",
        versionCode = 200,
        manufacturer = "Example",
        model = "Phone",
        androidRelease = "16",
        sdkInt = 36,
    )

    @Test
    fun `uncaught report includes cause and lifecycle context without sensitive values`() {
        val throwable = IllegalStateException(
            "connection failed for 192.168.1.20",
            IllegalArgumentException("bad device AA:BB:CC:DD:EE:FF"),
        )
        val report = CrashReportFormatter.formatUncaughtException(
            environment = environment,
            timestampMs = 1_750_000_000_000,
            threadName = "main",
            throwable = throwable,
            lifecycleBreadcrumbs = listOf("100 service startup begin startId=4"),
            runtimeLogs = listOf(
                "Proxy listening at 10.0.0.2",
                "psk=QRTIErOb/fcE9Ukd/5qA3RGYMn0Y+p06U58SCtOXvPc=",
            ),
        )

        assertTrue(report.contains("IllegalStateException"))
        assertTrue(report.contains("IllegalArgumentException"))
        assertTrue(report.contains("service startup begin startId=4"))
        assertTrue(report.contains("[redacted-ip]"))
        assertTrue(report.contains("[redacted-mac]"))
        assertTrue(report.contains("psk=[redacted]"))
        assertFalse(report.contains("192.168.1.20"))
        assertFalse(report.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(report.contains("QRTIErOb/fcE9Ukd/5qA3RGYMn0Y+p06U58SCtOXvPc="))
        assertEquals(1_750_000_000_000, CrashReportFormatter.eventTimestamp(report))
    }

    @Test
    fun `process exit report is bounded and preserves reason`() {
        val report = CrashReportFormatter.formatProcessExit(
            environment = environment,
            exit = ProcessExitSnapshot(
                timestampMs = 1_750_000_000_001,
                reason = "ANR",
                description = "service did not respond",
                status = 0,
                importance = 125,
                processName = "com.example.proxy",
                pssKb = 42_000,
                rssKb = 84_000,
                lifecycleSummary = "v1|service teardown begin",
                trace = "x".repeat(CrashReportFormatter.MAX_REPORT_CHARS * 2),
            ),
            lifecycleBreadcrumbs = emptyList(),
        )

        assertTrue(report.contains("Reason: ANR"))
        assertTrue(report.contains("Last lifecycle state: v1|service teardown begin"))
        assertTrue(report.length <= CrashReportFormatter.MAX_REPORT_CHARS)
        assertTrue(report.contains("...[section truncated]"))
    }

    @Test
    fun `sanitizer handles compressed ipv6 json secrets and unpadded base64`() {
        val standaloneSecret = "A".repeat(42) + "/"
        val sanitized = CrashReportFormatter.sanitize(
            "endpoint=fe80::1 token=\"visible-token\" raw=$standaloneSecret",
        )

        assertTrue(sanitized.contains("[redacted-ip]"))
        assertTrue(sanitized.contains("token=[redacted]"))
        assertTrue(sanitized.contains("[redacted-secret]"))
        assertFalse(sanitized.contains("fe80::1"))
        assertFalse(sanitized.contains("visible-token"))
        assertFalse(sanitized.contains(standaloneSecret))
    }

    @Test
    fun `crash handler delegates even when persistence fails`() {
        val crash = IllegalStateException("boom")
        var delegatedThread: Thread? = null
        var delegatedThrowable: Throwable? = null
        var terminated = false
        val delegate = Thread.UncaughtExceptionHandler { thread, throwable ->
            delegatedThread = thread
            delegatedThrowable = throwable
        }
        val handler = PersistingUncaughtExceptionHandler(
            persist = { _, _ -> error("disk unavailable") },
            delegate = delegate,
            terminateProcess = { terminated = true },
        )

        handler.uncaughtException(Thread.currentThread(), crash)

        assertEquals(Thread.currentThread(), delegatedThread)
        assertEquals(crash, delegatedThrowable)
        assertFalse(terminated)
    }

    @Test
    fun `historical exit never replaces a newer or richer Java report`() {
        assertFalse(
            HistoricalExitReportPolicy.shouldReplace(
                existingTimestampMs = 2_000,
                candidateTimestampMs = 1_000,
                candidateIsJavaCrash = false,
            ),
        )
        assertFalse(
            HistoricalExitReportPolicy.shouldReplace(
                existingTimestampMs = 10_000,
                candidateTimestampMs = 10_010,
                candidateIsJavaCrash = true,
            ),
        )
        assertTrue(
            HistoricalExitReportPolicy.shouldReplace(
                existingTimestampMs = 1_000,
                candidateTimestampMs = 2_000,
                candidateIsJavaCrash = false,
            ),
        )
        assertTrue(
            HistoricalExitReportPolicy.shouldReplace(
                existingTimestampMs = null,
                candidateTimestampMs = 2_000,
                candidateIsJavaCrash = true,
            ),
        )
    }

    @Test
    fun `crash handler terminates when Android supplied no delegate`() {
        var terminated = false
        val handler = PersistingUncaughtExceptionHandler(
            persist = { _, _ -> Unit },
            delegate = null,
            terminateProcess = { terminated = true },
        )

        handler.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        assertTrue(terminated)
    }
}
