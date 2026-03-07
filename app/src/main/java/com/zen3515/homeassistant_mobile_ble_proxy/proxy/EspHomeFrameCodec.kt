package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object EspHomeFrameCodec {
    private const val PREAMBLE: Int = 0x00

    data class Frame(
        val typeId: Int,
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false

            return typeId == other.typeId && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = typeId
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    @Throws(IOException::class)
    fun readFrame(input: InputStream): Frame? {
        val preamble = input.read()
        if (preamble == -1) {
            return null
        }
        if (preamble != PREAMBLE) {
            throw IOException("Invalid ESPHome frame preamble: $preamble")
        }

        val payloadLength = readVariableLengthInt(input)
        val typeId = readVariableLengthInt(input)

        if (payloadLength < 0 || payloadLength > 4 * 1024 * 1024) {
            throw IOException("Invalid payload length: $payloadLength")
        }

        val payload = ByteArray(payloadLength)
        readFully(input, payload)
        return Frame(typeId = typeId, payload = payload)
    }

    @Throws(IOException::class)
    fun writeFrame(output: OutputStream, typeId: Int, payload: ByteArray) {
        output.write(PREAMBLE)
        writeVariableLengthInt(output, payload.size)
        writeVariableLengthInt(output, typeId)
        output.write(payload)
        output.flush()
    }

    @Throws(IOException::class)
    private fun readVariableLengthInt(input: InputStream): Int {
        var result = 0
        var shift = 0

        while (shift < 35) {
            val value = input.read()
            if (value == -1) {
                throw IOException("Unexpected EOF while reading variable-length integer")
            }

            result = result or ((value and 0x7F) shl shift)
            if ((value and 0x80) == 0) {
                return result
            }
            shift += 7
        }

        throw IOException("Malformed variable-length integer")
    }

    @Throws(IOException::class)
    private fun writeVariableLengthInt(output: OutputStream, value: Int) {
        var v = value
        while (true) {
            if ((v and 0x7F.inv()) == 0) {
                output.write(v)
                return
            }
            output.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }

    @Throws(IOException::class)
    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) {
                throw IOException("Unexpected EOF while reading frame payload")
            }
            offset += read
        }
    }
}
