package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.WireFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

class EspHomeProtoCodecTest {

    @Test
    fun `parseScannerMode reads passive and active`() {
        val passivePayload = encodeProto { writeEnum(1, 0) }
        val activePayload = encodeProto { writeEnum(1, 1) }

        assertEquals(ScannerMode.PASSIVE, EspHomeProtoCodec.parseScannerMode(passivePayload))
        assertEquals(ScannerMode.ACTIVE, EspHomeProtoCodec.parseScannerMode(activePayload))
    }

    @Test
    fun `parseScannerMode treats absent mode as passive proto default`() {
        assertEquals(ScannerMode.PASSIVE, EspHomeProtoCodec.parseScannerMode(ByteArray(0)))
    }

    @Test
    fun `parseScannerMode rejects unknown enum`() {
        val unknownPayload = encodeProto { writeEnum(1, 7) }
        assertNull(EspHomeProtoCodec.parseScannerMode(unknownPayload))
    }

    @Test
    fun `parseAddress reads uint64 field`() {
        val address = 0xAABBCCDDEEFFL
        val payload = encodeProto { writeUInt64(1, address) }

        assertEquals(address, EspHomeProtoCodec.parseAddress(payload))
    }

    @Test
    fun `parseAddressHandle reads both fields`() {
        val address = 0x112233445566L
        val handle = 513
        val payload = encodeProto {
            writeUInt64(1, address)
            writeUInt32(2, handle)
        }

        val parsed = EspHomeProtoCodec.parseAddressHandle(payload)
        assertNotNull(parsed)
        assertEquals(address, parsed?.address)
        assertEquals(handle, parsed?.handle)
    }

    @Test
    fun `parseDeviceRequest reads type and address type`() {
        val payload = encodeProto {
            writeUInt64(1, 0xA1B2C3D4E5F6L)
            writeEnum(2, 5)
            writeBool(3, true)
            writeUInt32(4, 1)
        }

        val parsed = EspHomeProtoCodec.parseDeviceRequest(payload)
        assertNotNull(parsed)
        assertEquals(0xA1B2C3D4E5F6L, parsed?.address)
        assertEquals(EspHomeProtoCodec.BluetoothDeviceRequestType.CONNECT_V3_WITHOUT_CACHE, parsed?.requestType)
        assertEquals(true, parsed?.hasAddressType)
        assertEquals(1, parsed?.addressType)
    }

    @Test
    fun `parseGattWriteRequest reads payload`() {
        val payloadBytes = byteArrayOf(1, 2, 3, 4)
        val payload = encodeProto {
            writeUInt64(1, 0x1234L)
            writeUInt32(2, 0x44)
            writeBool(3, true)
            writeByteArray(4, payloadBytes)
        }

        val parsed = EspHomeProtoCodec.parseGattWriteRequest(payload)
        assertNotNull(parsed)
        assertEquals(0x1234L, parsed?.address)
        assertEquals(0x44, parsed?.handle)
        assertEquals(true, parsed?.response)
        assertArrayEquals(payloadBytes, parsed?.data)
    }

    @Test
    fun `parseGattWriteDescriptorRequest reads payload`() {
        val payloadBytes = byteArrayOf(9, 8, 7)
        val payload = encodeProto {
            writeUInt64(1, 0xABCDL)
            writeUInt32(2, 0x55)
            writeByteArray(3, payloadBytes)
        }

        val parsed = EspHomeProtoCodec.parseGattWriteDescriptorRequest(payload)
        assertNotNull(parsed)
        assertEquals(0xABCDL, parsed?.address)
        assertEquals(0x55, parsed?.handle)
        assertArrayEquals(payloadBytes, parsed?.data)
    }

    @Test
    fun `parseGattNotifyRequest reads payload`() {
        val payload = encodeProto {
            writeUInt64(1, 0x55AAL)
            writeUInt32(2, 0x66)
            writeBool(3, true)
        }

        val parsed = EspHomeProtoCodec.parseGattNotifyRequest(payload)
        assertNotNull(parsed)
        assertEquals(0x55AAL, parsed?.address)
        assertEquals(0x66, parsed?.handle)
        assertEquals(true, parsed?.enable)
    }

    @Test
    fun `parseConnectionParamsRequest reads all upstream fields`() {
        val payload = encodeProto {
            writeUInt64(1, 0xAABBCCDDEEFFL)
            writeUInt32(2, 24)
            writeUInt32(3, 40)
            writeUInt32(4, 2)
            writeUInt32(5, 500)
        }

        val parsed = EspHomeProtoCodec.parseConnectionParamsRequest(payload)

        assertNotNull(parsed)
        assertEquals(0xAABBCCDDEEFFL, parsed?.address)
        assertEquals(24L, parsed?.minInterval)
        assertEquals(40L, parsed?.maxInterval)
        assertEquals(2L, parsed?.latency)
        assertEquals(500L, parsed?.timeout)
        assertTrue(checkNotNull(parsed).fitsBleParameterWidth())
    }

    @Test
    fun `parseConnectionParamsRequest preserves uint32 values and clamps only at platform boundary`() {
        val payload = encodeProto {
            writeUInt32(2, -1)
            writeUInt32(3, 65_536)
        }

        val parsed = checkNotNull(EspHomeProtoCodec.parseConnectionParamsRequest(payload))

        assertEquals(0xFFFF_FFFFL, parsed.minInterval)
        assertEquals(65_536L, parsed.maxInterval)
        assertFalse(parsed.fitsBleParameterWidth())
        assertEquals(65_535L, parsed.clampedToBleParameterWidth().minInterval)
        assertEquals(65_535L, parsed.clampedToBleParameterWidth().maxInterval)
    }

    @Test
    fun `parseConnectionParamsRequest accepts protobuf defaults and rejects malformed input`() {
        assertEquals(
            EspHomeProtoCodec.ConnectionParamsRequest(0, 0, 0, 0, 0),
            EspHomeProtoCodec.parseConnectionParamsRequest(ByteArray(0)),
        )
        assertNull(EspHomeProtoCodec.parseConnectionParamsRequest(byteArrayOf(0x10, 0x80.toByte())))
    }

    @Test
    fun `encodeGattReadResponse writes fields`() {
        val payload = EspHomeProtoCodec.encodeGattReadResponse(
            address = 0xCAFEL,
            handle = 0x77,
            data = byteArrayOf(1, 2, 3),
        )

        var parsedAddress = 0L
        var parsedHandle = 0
        var parsedData = ByteArray(0)

        parseFields(payload) { input, fieldNumber, _ ->
            when (fieldNumber) {
                1 -> parsedAddress = input.readUInt64()
                2 -> parsedHandle = input.readUInt32()
                3 -> parsedData = input.readByteArray()
            }
        }

        assertEquals(0xCAFEL, parsedAddress)
        assertEquals(0x77, parsedHandle)
        assertArrayEquals(byteArrayOf(1, 2, 3), parsedData)
    }

    @Test
    fun `encodeDevicePairingResponse writes fields`() {
        val payload = EspHomeProtoCodec.encodeDevicePairingResponse(
            address = 0xABCDL,
            paired = true,
            error = 0,
        )

        var parsedAddress = 0L
        var parsedPaired = false
        var parsedError = -1

        parseFields(payload) { input, fieldNumber, _ ->
            when (fieldNumber) {
                1 -> parsedAddress = input.readUInt64()
                2 -> parsedPaired = input.readBool()
                3 -> parsedError = input.readInt32()
            }
        }

        assertEquals(0xABCDL, parsedAddress)
        assertTrue(parsedPaired)
        assertEquals(0, parsedError)
    }

    @Test
    fun `encodeDeviceClearCacheResponse writes fields`() {
        val payload = EspHomeProtoCodec.encodeDeviceClearCacheResponse(
            address = 0x2222L,
            success = false,
            error = -95,
        )

        var parsedAddress = 0L
        var parsedSuccess = true
        var parsedError = 0

        parseFields(payload) { input, fieldNumber, _ ->
            when (fieldNumber) {
                1 -> parsedAddress = input.readUInt64()
                2 -> parsedSuccess = input.readBool()
                3 -> parsedError = input.readInt32()
            }
        }

        assertEquals(0x2222L, parsedAddress)
        assertFalse(parsedSuccess)
        assertEquals(-95, parsedError)
    }

    @Test
    fun `encodeConnectionParamsResponse writes address and error`() {
        val payload = EspHomeProtoCodec.encodeConnectionParamsResponse(
            address = 0xAABBCCDDEEFFL,
            error = -95,
        )
        var address = 0L
        var error = 0

        parseFields(payload) { input, fieldNumber, _ ->
            when (fieldNumber) {
                1 -> address = input.readUInt64()
                2 -> error = input.readInt32()
            }
        }

        assertEquals(0xAABBCCDDEEFFL, address)
        assertEquals(-95, error)
    }

    @Test
    fun `encodeGattGetServicesResponse contains services`() {
        val payload = EspHomeProtoCodec.encodeGattGetServicesResponse(
            address = 0x1122L,
            services = listOf(
                EspHomeProtoCodec.GattService(
                    uuid = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"),
                    handle = 1,
                    characteristics = listOf(
                        EspHomeProtoCodec.GattCharacteristic(
                            uuid = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb"),
                            handle = 2,
                            properties = 0x12,
                            descriptors = listOf(
                                EspHomeProtoCodec.GattDescriptor(
                                    uuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
                                    handle = 3,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        var parsedAddress = 0L
        var serviceCount = 0

        parseFields(payload) { input, fieldNumber, _ ->
            when (fieldNumber) {
                1 -> parsedAddress = input.readUInt64()
                2 -> {
                    val servicePayload = input.readByteArray()
                    serviceCount += 1
                    assertTrue(servicePayload.isNotEmpty())
                }
            }
        }

        assertEquals(0x1122L, parsedAddress)
        assertEquals(1, serviceCount)
    }

    @Test
    fun `large GATT service trees are split into bounded response batches`() {
        val uuid = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val services = (1..60).map { index ->
            EspHomeProtoCodec.GattService(
                uuid = uuid,
                handle = index * 3,
                characteristics = listOf(
                    EspHomeProtoCodec.GattCharacteristic(
                        uuid = uuid,
                        handle = index * 3 + 1,
                        properties = 0x12,
                        descriptors = listOf(
                            EspHomeProtoCodec.GattDescriptor(uuid = uuid, handle = index * 3 + 2),
                        ),
                    ),
                ),
            )
        }

        val batches = EspHomeProtoCodec.encodeGattGetServicesResponseBatches(
            address = 0x112233445566L,
            services = services,
            maxPayloadBytes = 300,
        )

        assertTrue(batches.size > 1)
        assertTrue(batches.all { it.size <= 300 })
        var encodedServiceCount = 0
        for (batch in batches) {
            parseFields(batch) { input, fieldNumber, tag ->
                when (fieldNumber) {
                    1 -> input.readUInt64()
                    2 -> {
                        input.readByteArray()
                        encodedServiceCount += 1
                    }
                    else -> input.skipField(tag)
                }
            }
        }
        assertEquals(services.size, encodedServiceCount)
    }

    @Test
    fun `raw advertisement encoder preserves forced zero value fields`() {
        val payload = EspHomeProtoCodec.encodeRawAdvertisementsResponse(
            listOf(RawAdvertisement(address = 0, rssi = 0, addressType = 0, data = ByteArray(0))),
        )
        var nestedPayload = ByteArray(0)
        parseFields(payload) { input, fieldNumber, tag ->
            if (fieldNumber == 1) {
                nestedPayload = input.readByteArray()
            } else {
                input.skipField(tag)
            }
        }

        val seenFields = mutableSetOf<Int>()
        parseFields(nestedPayload) { input, fieldNumber, tag ->
            seenFields += fieldNumber
            when (fieldNumber) {
                1 -> input.readUInt64()
                2 -> input.readSInt32()
                3 -> input.readUInt32()
                4 -> input.readByteArray()
                else -> input.skipField(tag)
            }
        }

        assertEquals(setOf(1, 2, 3, 4), seenFields)
    }

    @Test
    fun `canParse detects malformed payload`() {
        assertTrue(EspHomeProtoCodec.canParse(byteArrayOf()))
        assertFalse(EspHomeProtoCodec.canParse(byteArrayOf(0x08, 0x80.toByte())))
    }

    private fun parseFields(
        payload: ByteArray,
        reader: (input: CodedInputStream, fieldNumber: Int, tag: Int) -> Unit,
    ) {
        val input = CodedInputStream.newInstance(payload)
        while (!input.isAtEnd) {
            val tag = input.readTag()
            if (tag == 0) {
                break
            }
            val fieldNumber = WireFormat.getTagFieldNumber(tag)
            reader(input, fieldNumber, tag)
        }
    }

    private fun encodeProto(writer: CodedOutputStream.() -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        val coded = CodedOutputStream.newInstance(output)
        coded.writer()
        coded.flush()
        return output.toByteArray()
    }
}
