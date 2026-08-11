package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import android.os.SystemClock

/**
 * Protocol-level state machine mirroring NicoIIT/esphome-ble_adv_proxy's ble_adv_proxy.cpp:
 * ignore lists, a duplicate-suppression window for received raw advertisements, and the
 * best-effort "setup done" gate before the esphome.ble_adv.raw_adv event is allowed to fire.
 */
class BleAdvProxyManager(
    private val onLog: (String) -> Unit = {},
) {
    data class RawAdvEvent(val hex: String, val mac: String)

    @Volatile
    var setupDone: Boolean = false
        private set

    private val lock = Any()
    private var ignoreDurationMs: Long = DEFAULT_IGNORE_DURATION_MS
    private var ignoredCompanyIds: Set<Int> = emptySet()
    private var ignoredMacs: Set<String> = emptySet()
    private val dupePackets = mutableListOf<DupeEntry>()

    private data class DupeEntry(val data: ByteArray, var expiresAtMs: Long)

    fun handleSetup(ignDurationMs: Float, ignoredCids: List<Float>, ignoredMacsList: List<String>) {
        synchronized(lock) {
            ignoreDurationMs = ignDurationMs.toLong().coerceAtLeast(0)
            dupePackets.clear()
            ignoredCompanyIds = ignoredCids.map { it.toInt() and 0xFFFF }.toSet()
            ignoredMacs = ignoredMacsList.map { it.uppercase() }.toSet()
        }
        setupDone = true
        onLog(
            "ble_adv setup: ${ignoredCompanyIds.size} ignored company id(s), " +
                "${ignoredMacs.size} ignored mac(s), ignore_duration=${ignoreDurationMs}ms",
        )
    }

    /**
     * Registers raw hex payloads that should be treated as recent dupes (suppressed if re-received).
     * A null [ignDurationMs] uses the duration configured via [handleSetup], matching
     * BleAdvProxy::on_advertise_v0 delegating to the setup-level dupe_ignore_duration_.
     */
    fun registerIgnoredAdvertisements(ignoredAdvsHex: List<String>, ignDurationMs: Float? = null) {
        setupDone = true // best-effort, mirrors on_advertise_v1 setting setup_done_ = true
        if (ignoredAdvsHex.isEmpty()) return
        val expiresAtMs = synchronized(lock) {
            SystemClock.elapsedRealtime() + (ignDurationMs?.toLong() ?: ignoreDurationMs).coerceAtLeast(0)
        }
        for (hex in ignoredAdvsHex) {
            val bytes = runCatching { BleAdvertiseManager.hexToBytes(hex) }.getOrNull() ?: continue
            synchronized(lock) { checkAddDupePacket(bytes, expiresAtMs) }
        }
    }

    /**
     * Feeds a scanned raw advertisement through the ble_adv ignore/dupe pipeline.
     * Returns the event payload to fire towards Home Assistant, or null if it should be dropped.
     */
    fun onRawRecv(macAddress: String, data: ByteArray): RawAdvEvent? {
        if (!setupDone) return null
        if (data.size < MIN_VIABLE_PACKET_LEN) return null

        // Matches ble_adv_proxy.cpp: cid = (adv[3] << 8) + adv[2], i.e. the company id of the
        // leading Manufacturer-Specific-Data AD structure.
        val companyId = ((data[3].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        val macUpper = macAddress.uppercase()

        val isNew = synchronized(lock) {
            if (companyId in ignoredCompanyIds) return null
            if (macUpper in ignoredMacs) return null
            checkAddDupePacket(data, SystemClock.elapsedRealtime() + ignoreDurationMs)
        }
        if (!isNew) return null

        return RawAdvEvent(hex = BleAdvertiseManager.formatHex(data), mac = macUpper)
    }

    /** Caller must hold [lock]. Returns true if newly added (i.e. not a recently-seen dupe). */
    private fun checkAddDupePacket(data: ByteArray, expiresAtMs: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        dupePackets.removeAll { it.expiresAtMs in 1 until now }

        val existing = dupePackets.firstOrNull { entry ->
            entry.data.size <= data.size && data.copyOfRange(0, entry.data.size).contentEquals(entry.data)
        }
        if (existing != null) {
            existing.expiresAtMs = expiresAtMs
            return false
        }
        dupePackets.add(DupeEntry(data, expiresAtMs))
        return true
    }

    companion object {
        private const val DEFAULT_IGNORE_DURATION_MS = 20_000L
        private const val MIN_VIABLE_PACKET_LEN = 5
    }
}
