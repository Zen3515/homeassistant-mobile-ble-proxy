package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import java.io.InputStream
import java.io.OutputStream

internal interface EspHomeTransport {
    fun readFrame(): EspHomeFrameCodec.Frame?
    fun writeFrame(typeId: Int, payload: ByteArray)
}

internal class EspHomePlaintextTransport(
    private val input: InputStream,
    private val output: OutputStream,
) : EspHomeTransport {
    override fun readFrame(): EspHomeFrameCodec.Frame? = EspHomeFrameCodec.readFrame(input)

    override fun writeFrame(typeId: Int, payload: ByteArray) {
        EspHomeFrameCodec.writeFrame(output, typeId, payload)
    }
}

