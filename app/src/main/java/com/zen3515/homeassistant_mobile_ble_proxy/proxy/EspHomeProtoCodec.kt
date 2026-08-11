@file:Suppress("SpellCheckingInspection")

package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.WireFormat
import java.io.ByteArrayOutputStream
import java.util.UUID

object EspHomeProtoCodec {
    data class AddressHandle(
        val address: Long,
        val handle: Int,
    )

    enum class BluetoothDeviceRequestType {
        CONNECT,
        DISCONNECT,
        PAIR,
        UNPAIR,
        CONNECT_V3_WITH_CACHE,
        CONNECT_V3_WITHOUT_CACHE,
        CLEAR_CACHE,
    }

    data class DeviceRequest(
        val address: Long,
        val requestType: BluetoothDeviceRequestType,
        val hasAddressType: Boolean,
        val addressType: Int,
    )

    class GattWriteRequest(
        val address: Long,
        val handle: Int,
        val response: Boolean,
        val data: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is GattWriteRequest) return false

            return address == other.address &&
                handle == other.handle &&
                response == other.response &&
                data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = address.hashCode()
            result = 31 * result + handle
            result = 31 * result + response.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    class GattWriteDescriptorRequest(
        val address: Long,
        val handle: Int,
        val data: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is GattWriteDescriptorRequest) return false

            return address == other.address &&
                handle == other.handle &&
                data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = address.hashCode()
            result = 31 * result + handle
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    data class GattNotifyRequest(
        val address: Long,
        val handle: Int,
        val enable: Boolean,
    )

    data class ConnectionParamsRequest(
        val address: Long,
        val minInterval: Long,
        val maxInterval: Long,
        val latency: Long,
        val timeout: Long,
    ) {
        fun fitsBleParameterWidth(): Boolean =
            minInterval <= UShort.MAX_VALUE.toLong() &&
                maxInterval <= UShort.MAX_VALUE.toLong() &&
                latency <= UShort.MAX_VALUE.toLong() &&
                timeout <= UShort.MAX_VALUE.toLong()

        fun clampedToBleParameterWidth(): ConnectionParamsRequest = copy(
            minInterval = minInterval.coerceAtMost(UShort.MAX_VALUE.toLong()),
            maxInterval = maxInterval.coerceAtMost(UShort.MAX_VALUE.toLong()),
            latency = latency.coerceAtMost(UShort.MAX_VALUE.toLong()),
            timeout = timeout.coerceAtMost(UShort.MAX_VALUE.toLong()),
        )
    }

    data class GattDescriptor(
        val uuid: UUID,
        val handle: Int,
    )

    enum class ServiceArgType(val protoValue: Int) {
        BOOL(0),
        INT(1),
        FLOAT(2),
        STRING(3),
        BOOL_ARRAY(4),
        INT_ARRAY(5),
        FLOAT_ARRAY(6),
        STRING_ARRAY(7),
    }

    data class ExecuteServiceArgument(
        val bool: Boolean = false,
        val legacyInt: Int = 0,
        val float: Float = 0f,
        val string: String = "",
        val int: Int = 0,
        val boolArray: List<Boolean> = emptyList(),
        val intArray: List<Int> = emptyList(),
        val floatArray: List<Float> = emptyList(),
        val stringArray: List<String> = emptyList(),
    )

    data class ExecuteServiceRequestMsg(
        val key: Int,
        val args: List<ExecuteServiceArgument>,
    )

    data class GattCharacteristic(
        val uuid: UUID,
        val handle: Int,
        val properties: Int,
        val descriptors: List<GattDescriptor>,
    )

    data class GattService(
        val uuid: UUID,
        val handle: Int,
        val characteristics: List<GattCharacteristic>,
    )

    fun canParse(payload: ByteArray): Boolean {
        return runCatching {
            val input = CodedInputStream.newInstance(payload)
            while (!input.isAtEnd) {
                val tag = input.readTag()
                if (tag == 0) {
                    break
                }
                if (!input.skipField(tag)) {
                    break
                }
            }
        }.isSuccess
    }

    fun parseScannerMode(payload: ByteArray): ScannerMode? {
        var modeValue = 0
        val ok = parse(payload) { input, fieldNumber, tag ->
            when (fieldNumber) {
                1 -> modeValue = input.readEnum()
                else -> input.skipField(tag)
            }
        }
        if (!ok) return null

        return when (modeValue) {
            0 -> ScannerMode.PASSIVE
            1 -> ScannerMode.ACTIVE
            else -> null
        }
    }

    fun parseAddress(payload: ByteArray): Long? {
        var address = 0L
        var hasAddress = false

        val ok = parse(payload) { input, fieldNumber, tag ->
            when (fieldNumber) {
                1 -> {
                    address = input.readUInt64()
                    hasAddress = true
                }

                else -> input.skipField(tag)
            }
        }

        return if (ok && hasAddress) address else null
    }

    fun parseAddressHandle(payload: ByteArray): AddressHandle? {
        var address = 0L
        var handle = 0
        var hasAddress = false
        var hasHandle = false

        val ok = parse(payload) { input, fieldNumber, tag ->
            when (fieldNumber) {
                1 -> {
                    address = input.readUInt64()
                    hasAddress = true
                }

                2 -> {
                    handle = input.readUInt32()
                    hasHandle = true
                }

                else -> input.skipField(tag)
            }
        }

        return if (ok && hasAddress && hasHandle) AddressHandle(address, handle) else null
    }

    fun parseDeviceRequest(payload: ByteArray): DeviceRequest? {
        var address = 0L
        var hasAddress = false
        var requestTypeValue = -1
        var hasRequestType = false
        var hasAddressType = false
        var addressType = 0

        val ok = parse(payload) { input, fieldNumber, tag ->
            when (fieldNumber) {
                1 -> {
                    address = input.readUInt64()
                    hasAddress = true
                }

                2 -> {
                    requestTypeValue = input.readEnum()
                    hasRequestType = true
                }

                3 -> hasAddressType = input.readBool()
                4 -> addressType = input.readUInt32()
                else -> input.skipField(tag)
            }
        }
        if (!ok || !hasAddress || !hasRequestType) {
            return null
        }

        val requestType = when (requestTypeValue) {
            0 -> BluetoothDeviceRequestType.CONNECT
            1 -> BluetoothDeviceRequestType.DISCONNECT
            2 -> BluetoothDeviceRequestType.PAIR
            3 -> BluetoothDeviceRequestType.UNPAIR
            4 -> BluetoothDeviceRequestType.CONNECT_V3_WITH_CACHE
            5 -> BluetoothDeviceRequestType.CONNECT_V3_WITHOUT_CACHE
            6 -> BluetoothDeviceRequestType.CLEAR_CACHE
            else -> null
        } ?: return null

        return DeviceRequest(
            address = address,
            requestType = requestType,
            hasAddressType = hasAddressType,
            addressType = addressType,
        )
    }

    fun parseGattWriteRequest(payload: ByteArray): GattWriteRequest? {
        var address = 0L
        var handle = 0
        var response = false
        var data = ByteArray(0)
        var hasAddress = false
        var hasHandle = false

        val ok = parse(payload) { input, fieldNumber, tag ->
            when (fieldNumber) {
                1 -> {
                    address = input.readUInt64()
                    hasAddress = true
                }

                2 -> {
                    handle = input.readUInt32()
                    hasHandle = true
                }

                3 -> response = input.readBool()
                4 -> data = input.readByteArray()
                else -> input.skipField(tag)
            }
        }

        if (!ok || !hasAddress || !hasHandle) {
            return null
        }

        return GattWriteRequest(
            address = address,
            handle = handle,
            response = response,
            data = data,
        )
    }

    fun parseGattWriteDescriptorRequest(payload: ByteArray): GattWriteDescriptorRequest? {
        var address = 0L
        var handle = 0
        var data = ByteArray(0)
        var hasAddress = false
        var hasHandle = false

        val ok = parse(payload) { input, fieldNumber, tag ->
            when (fieldNumber) {
                1 -> {
                    address = input.readUInt64()
                    hasAddress = true
                }

                2 -> {
                    handle = input.readUInt32()
                    hasHandle = true
                }

                3 -> data = input.readByteArray()
                else -> input.skipField(tag)
            }
        }

        if (!ok || !hasAddress || !hasHandle) {
            return null
        }

        return GattWriteDescriptorRequest(
            address = address,
            handle = handle,
            data = data,
        )
    }

    fun parseGattNotifyRequest(payload: ByteArray): GattNotifyRequest? {
        var address = 0L
        var handle = 0
        var enable = false
        var hasAddress = false
        var hasHandle = false

        val ok = parse(payload) { input, fieldNumber, tag ->
            when (fieldNumber) {
                1 -> {
                    address = input.readUInt64()
                    hasAddress = true
                }

                2 -> {
                    handle = input.readUInt32()
                    hasHandle = true
                }

                3 -> enable = input.readBool()
                else -> input.skipField(tag)
            }
        }

        if (!ok || !hasAddress || !hasHandle) {
            return null
        }

        return GattNotifyRequest(
            address = address,
            handle = handle,
            enable = enable,
        )
    }

    fun parseExecuteServiceRequest(payload: ByteArray): ExecuteServiceRequestMsg? {
        var key = 0
        var hasKey = false
        val args = mutableListOf<ExecuteServiceArgument>()

        val ok = parse(payload) { input, fieldNumber, tag ->
            when (fieldNumber) {
                1 -> {
                    key = input.readFixed32()
                    hasKey = true
                }

                2 -> args += parseExecuteServiceArgument(input.readByteArray())
                else -> input.skipField(tag)
            }
        }

        return if (ok && hasKey) ExecuteServiceRequestMsg(key, args) else null
    }

    private fun parseExecuteServiceArgument(payload: ByteArray): ExecuteServiceArgument {
        var bool = false
        var legacyInt = 0
        var float = 0f
        var string = ""
        var int = 0
        val boolArray = mutableListOf<Boolean>()
        val intArray = mutableListOf<Int>()
        val floatArray = mutableListOf<Float>()
        val stringArray = mutableListOf<String>()

        parse(payload) { input, fieldNumber, tag ->
            when (fieldNumber) {
                1 -> bool = input.readBool()
                2 -> legacyInt = input.readInt32()
                3 -> float = input.readFloat()
                4 -> string = input.readString()
                5 -> int = input.readSInt32()
                6 -> boolArray += input.readBool()
                7 -> intArray += input.readSInt32()
                8 -> floatArray += input.readFloat()
                9 -> stringArray += input.readString()
                else -> input.skipField(tag)
            }
        }

        return ExecuteServiceArgument(
            bool = bool,
            legacyInt = legacyInt,
            float = float,
            string = string,
            int = int,
            boolArray = boolArray,
            intArray = intArray,
            floatArray = floatArray,
            stringArray = stringArray,
        )
    }

    fun parseConnectionParamsRequest(payload: ByteArray): ConnectionParamsRequest? {
        var address = 0L
        var minInterval = 0L
        var maxInterval = 0L
        var latency = 0L
        var timeout = 0L

        val ok = parse(payload) { input, fieldNumber, tag ->
            when (fieldNumber) {
                1 -> address = input.readUInt64()
                2 -> minInterval = Integer.toUnsignedLong(input.readUInt32())
                3 -> maxInterval = Integer.toUnsignedLong(input.readUInt32())
                4 -> latency = Integer.toUnsignedLong(input.readUInt32())
                5 -> timeout = Integer.toUnsignedLong(input.readUInt32())
                else -> input.skipField(tag)
            }
        }
        if (!ok) return null

        return ConnectionParamsRequest(
            address = address,
            minInterval = minInterval,
            maxInterval = maxInterval,
            latency = latency,
            timeout = timeout,
        )
    }

    fun encodeHelloResponse(
        apiMajor: Int,
        apiMinor: Int,
        serverInfo: String,
        name: String,
    ): ByteArray {
        return encode {
            writeUInt32(1, apiMajor)
            writeUInt32(2, apiMinor)
            writeString(3, serverInfo)
            writeString(4, name)
        }
    }

    fun encodeDeviceInfoResponse(
        nodeName: String,
        macAddress: String,
        esphomeVersion: String,
        compilationTime: String,
        model: String,
        legacyBluetoothProxyVersion: Int,
        manufacturer: String,
        friendlyName: String,
        bluetoothProxyFeatureFlags: Int,
        bluetoothMacAddress: String,
        apiEncryptionSupported: Boolean,
    ): ByteArray {
        return encode {
            writeBool(1, false)
            writeString(2, nodeName)
            writeString(3, macAddress)
            writeString(4, esphomeVersion)
            writeString(5, compilationTime)
            writeString(6, model)
            writeUInt32(11, legacyBluetoothProxyVersion)
            writeString(12, manufacturer)
            writeString(13, friendlyName)
            writeUInt32(15, bluetoothProxyFeatureFlags)
            writeString(18, bluetoothMacAddress)
            writeBool(19, apiEncryptionSupported)
        }
    }

    fun encodeConnectionsFreeResponse(free: Int, limit: Int, allocated: List<Long> = emptyList()): ByteArray {
        return encode {
            writeUInt32(1, free)
            writeUInt32(2, limit)
            for (address in allocated) {
                writeUInt64(3, address)
            }
        }
    }

    fun encodeScannerStateResponse(
        scannerState: RuntimeScannerState,
        mode: ScannerMode,
    ): ByteArray {
        return encode {
            writeEnum(1, scannerState.toProtoValue())
            writeEnum(2, mode.toProtoValue())
            writeEnum(3, mode.toProtoValue())
        }
    }

    fun encodeDeviceConnectionResponse(address: Long, connected: Boolean, mtu: Int, error: Int): ByteArray {
        return encode {
            writeUInt64(1, address)
            writeBool(2, connected)
            writeUInt32(3, mtu)
            writeInt32(4, error)
        }
    }

    fun encodeDevicePairingResponse(address: Long, paired: Boolean, error: Int): ByteArray {
        return encode {
            writeUInt64(1, address)
            writeBool(2, paired)
            writeInt32(3, error)
        }
    }

    fun encodeDeviceUnpairingResponse(address: Long, success: Boolean, error: Int): ByteArray {
        return encode {
            writeUInt64(1, address)
            writeBool(2, success)
            writeInt32(3, error)
        }
    }

    fun encodeDeviceClearCacheResponse(address: Long, success: Boolean, error: Int): ByteArray {
        return encode {
            writeUInt64(1, address)
            writeBool(2, success)
            writeInt32(3, error)
        }
    }

    fun encodeGattGetServicesResponse(address: Long, services: List<GattService>): ByteArray {
        return encode {
            writeUInt64(1, address)
            for (service in services) {
                val servicePayload = encode {
                    writeUuidField(1, service.uuid)
                    writeUInt32(2, service.handle)
                    for (characteristic in service.characteristics) {
                        val characteristicPayload = encode {
                            writeUuidField(1, characteristic.uuid)
                            writeUInt32(2, characteristic.handle)
                            writeUInt32(3, characteristic.properties)
                            for (descriptor in characteristic.descriptors) {
                                val descriptorPayload = encode {
                                    writeUuidField(1, descriptor.uuid)
                                    writeUInt32(2, descriptor.handle)
                                }
                                writeNestedMessage(4, descriptorPayload)
                            }
                        }
                        writeNestedMessage(3, characteristicPayload)
                    }
                }
                writeNestedMessage(2, servicePayload)
            }
        }
    }

    fun encodeGattGetServicesResponseBatches(
        address: Long,
        services: List<GattService>,
        maxPayloadBytes: Int,
    ): List<ByteArray> {
        require(maxPayloadBytes > 0) { "maxPayloadBytes must be positive" }
        if (services.isEmpty()) return emptyList()

        val batches = mutableListOf<ByteArray>()
        val current = mutableListOf<GattService>()
        for (service in services) {
            val candidate = encodeGattGetServicesResponse(address, current + service)
            if (current.isNotEmpty() && candidate.size > maxPayloadBytes) {
                batches += encodeGattGetServicesResponse(address, current)
                current.clear()
            }
            current += service
        }
        if (current.isNotEmpty()) {
            // A single service can exceed the preferred size. It must still be sent because
            // splitting inside a service would change the ESPHome message structure.
            batches += encodeGattGetServicesResponse(address, current)
        }
        return batches
    }

    fun encodeGattGetServicesDoneResponse(address: Long): ByteArray {
        return encode {
            writeUInt64(1, address)
        }
    }

    fun encodeGattReadResponse(address: Long, handle: Int, data: ByteArray): ByteArray {
        return encode {
            writeUInt64(1, address)
            writeUInt32(2, handle)
            writeByteArray(3, data)
        }
    }

    fun encodeGattWriteResponse(address: Long, handle: Int): ByteArray {
        return encode {
            writeUInt64(1, address)
            writeUInt32(2, handle)
        }
    }

    fun encodeGattNotifyResponse(address: Long, handle: Int): ByteArray {
        return encode {
            writeUInt64(1, address)
            writeUInt32(2, handle)
        }
    }

    fun encodeGattNotifyDataResponse(address: Long, handle: Int, data: ByteArray): ByteArray {
        return encode {
            writeUInt64(1, address)
            writeUInt32(2, handle)
            writeByteArray(3, data)
        }
    }

    fun encodeGattErrorResponse(address: Long, handle: Int, error: Int): ByteArray {
        return encode {
            writeUInt64(1, address)
            writeUInt32(2, handle)
            writeInt32(3, error)
        }
    }

    fun encodeConnectionParamsResponse(address: Long, error: Int): ByteArray {
        return encode {
            writeUInt64(1, address)
            writeInt32(2, error)
        }
    }

    fun encodeRawAdvertisementsResponse(advertisements: List<RawAdvertisement>): ByteArray {
        return encode {
            for (advertisement in advertisements) {
                val nested = encode {
                    writeUInt64(1, advertisement.address)
                    writeSInt32(2, advertisement.rssi)
                    writeUInt32(3, advertisement.addressType)
                    writeByteArray(4, advertisement.data)
                }
                writeNestedMessage(1, nested)
            }
        }
    }

    fun encodeListEntitiesTextSensorResponse(objectId: String, key: Int, name: String): ByteArray {
        return encode {
            writeString(1, objectId)
            writeFixed32(2, key)
            writeString(3, name)
            writeEnum(7, ENTITY_CATEGORY_DIAGNOSTIC)
        }
    }

    fun encodeTextSensorStateResponse(key: Int, state: String): ByteArray {
        return encode {
            writeFixed32(1, key)
            writeString(2, state)
        }
    }

    fun encodeListEntitiesServicesResponse(
        name: String,
        key: Int,
        args: List<Pair<String, ServiceArgType>>,
    ): ByteArray {
        return encode {
            writeString(1, name)
            writeFixed32(2, key)
            for ((argName, argType) in args) {
                val argPayload = encode {
                    writeString(1, argName)
                    writeEnum(2, argType.protoValue)
                }
                writeNestedMessage(3, argPayload)
            }
        }
    }

    fun encodeHomeassistantServiceResponse(
        service: String,
        data: List<Pair<String, String>>,
        isEvent: Boolean,
    ): ByteArray {
        return encode {
            writeString(1, service)
            for ((mapKey, mapValue) in data) {
                val mapPayload = encode {
                    writeString(1, mapKey)
                    writeString(2, mapValue)
                }
                writeNestedMessage(2, mapPayload)
            }
            writeBool(5, isEvent)
        }
    }

    private fun RuntimeScannerState.toProtoValue(): Int {
        return when (this) {
            RuntimeScannerState.IDLE -> 0
            RuntimeScannerState.STARTING -> 1
            RuntimeScannerState.RUNNING -> 2
            RuntimeScannerState.FAILED -> 3
            RuntimeScannerState.STOPPING -> 4
            RuntimeScannerState.STOPPED -> 5
        }
    }

    private fun ScannerMode.toProtoValue(): Int {
        return when (this) {
            ScannerMode.PASSIVE -> 0
            ScannerMode.ACTIVE -> 1
        }
    }

    private const val ENTITY_CATEGORY_DIAGNOSTIC = 2

    private fun CodedOutputStream.writeNestedMessage(fieldNumber: Int, payload: ByteArray) {
        writeTag(fieldNumber, WireFormat.WIRETYPE_LENGTH_DELIMITED)
        writeUInt32NoTag(payload.size)
        writeRawBytes(payload)
    }

    private fun CodedOutputStream.writeUuidField(fieldNumber: Int, uuid: UUID) {
        writeUInt64(fieldNumber, uuid.mostSignificantBits)
        writeUInt64(fieldNumber, uuid.leastSignificantBits)
    }

    private inline fun parse(
        payload: ByteArray,
        reader: (input: CodedInputStream, fieldNumber: Int, tag: Int) -> Unit,
    ): Boolean {
        return runCatching {
            val input = CodedInputStream.newInstance(payload)
            while (!input.isAtEnd) {
                val tag = input.readTag()
                if (tag == 0) {
                    break
                }
                val fieldNumber = WireFormat.getTagFieldNumber(tag)
                reader(input, fieldNumber, tag)
            }
        }.isSuccess
    }

    private inline fun encode(writer: CodedOutputStream.() -> Unit): ByteArray {
        val outputStream = ByteArrayOutputStream()
        val coded = CodedOutputStream.newInstance(outputStream)
        coded.writer()
        coded.flush()
        return outputStream.toByteArray()
    }
}
