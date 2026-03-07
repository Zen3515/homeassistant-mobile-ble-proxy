package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import java.security.SecureRandom
import java.util.Locale

object ProxyIdentity {
    fun stableMacAddress(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_STABLE_MAC, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }

        val macBytes = ByteArray(6)
        SecureRandom().nextBytes(macBytes)

        // Set locally administered bit and clear multicast bit.
        macBytes[0] = ((macBytes[0].toInt() or 0x02) and 0xFE).toByte()

        val generated = macBytes.joinToString(":") { "%02X".format(Locale.US, it.toInt() and 0xFF) }
        prefs.edit { putString(KEY_STABLE_MAC, generated) }
        return generated
    }

    fun stableNoisePsk(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_STABLE_NOISE_PSK, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }

        val keyBytes = ByteArray(32)
        SecureRandom().nextBytes(keyBytes)
        val generated = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
        prefs.edit { putString(KEY_STABLE_NOISE_PSK, generated) }
        return generated
    }

    fun resolveMacAddress(context: Context, overrideMac: String): String {
        return normalizeMacAddress(overrideMac) ?: stableMacAddress(context)
    }

    fun normalizeMacAddress(value: String): String? {
        if (value.isBlank()) {
            return null
        }

        val clean = value
            .replace(":", "")
            .replace("-", "")
            .trim()

        if (clean.length != 12 || clean.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) {
            return null
        }

        return clean.uppercase(Locale.US)
            .chunked(2)
            .joinToString(":")
    }

    fun macToLong(macAddress: String): Long {
        val clean = macAddress.replace(":", "").replace("-", "")
        if (clean.length != 12) {
            return 0L
        }

        return clean.chunked(2).fold(0L) { acc, byteHex ->
            (acc shl 8) or (byteHex.toLongOrNull(16) ?: 0L)
        }
    }

    fun longToMac(address: Long): String {
        val parts = (5 downTo 0).map { index ->
            val value = (address shr (index * 8)).toInt() and 0xFF
            "%02X".format(Locale.US, value)
        }
        return parts.joinToString(":")
    }

    fun sanitizeServiceName(nodeName: String): String {
        val trimmed = nodeName.trim().ifBlank { "android_ble_proxy" }
        return trimmed.replace(Regex("[^A-Za-z0-9_-]"), "_").take(63)
    }

    private const val PREFS_NAME = "proxy_identity"
    private const val KEY_STABLE_MAC = "stable_mac_address"
    private const val KEY_STABLE_NOISE_PSK = "stable_noise_psk"
}
