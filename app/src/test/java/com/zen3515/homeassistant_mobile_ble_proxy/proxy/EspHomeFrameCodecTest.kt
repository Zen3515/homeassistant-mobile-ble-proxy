package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class EspHomeFrameCodecTest {

    @Test
    fun `writeFrame and readFrame round trip`() {
        val output = ByteArrayOutputStream()
        val payload = byteArrayOf(1, 2, 3, 4, 5)

        EspHomeFrameCodec.writeFrame(output, 127, payload)

        val frame = EspHomeFrameCodec.readFrame(ByteArrayInputStream(output.toByteArray()))
        requireNotNull(frame)

        assertEquals(127, frame.typeId)
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun `writeFrame and readFrame support varint type id`() {
        val output = ByteArrayOutputStream()
        val payload = byteArrayOf(9, 9, 9)

        EspHomeFrameCodec.writeFrame(output, 300, payload)

        val frame = EspHomeFrameCodec.readFrame(ByteArrayInputStream(output.toByteArray()))
        requireNotNull(frame)

        assertEquals(300, frame.typeId)
        assertArrayEquals(payload, frame.payload)
    }

    @Test(expected = IOException::class)
    fun `readFrame rejects invalid preamble`() {
        EspHomeFrameCodec.readFrame(ByteArrayInputStream(byteArrayOf(0x01, 0x00, 0x00)))
    }

    @Test
    fun `readFrame returns null on EOF`() {
        assertNull(EspHomeFrameCodec.readFrame(ByteArrayInputStream(byteArrayOf())))
    }
}

