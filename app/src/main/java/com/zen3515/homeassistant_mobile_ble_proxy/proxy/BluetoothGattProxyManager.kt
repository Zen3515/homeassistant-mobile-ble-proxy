package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import android.Manifest
import android.content.BroadcastReceiver
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.util.ArrayDeque
import java.util.UUID

class BluetoothGattProxyManager(
    private val context: Context,
    private val maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
    private val onEvent: (Event) -> Unit,
    private val onError: (String) -> Unit,
) {
    sealed interface Event {
        data class DeviceConnection(
            val address: Long,
            val connected: Boolean,
            val mtu: Int,
            val error: Int,
        ) : Event

        data class DevicePairing(
            val address: Long,
            val paired: Boolean,
            val error: Int,
        ) : Event

        data class DeviceUnpairing(
            val address: Long,
            val success: Boolean,
            val error: Int,
        ) : Event

        data class DeviceClearCache(
            val address: Long,
            val success: Boolean,
            val error: Int,
        ) : Event

        data class ConnectionsChanged(
            val free: Int,
            val limit: Int,
            val allocated: List<Long>,
        ) : Event

        data class GattServices(
            val address: Long,
            val services: List<EspHomeProtoCodec.GattService>,
        ) : Event

        data class GattServicesDone(
            val address: Long,
        ) : Event

        class GattRead(
            val address: Long,
            val handle: Int,
            val data: ByteArray,
        ) : Event

        data class GattWrite(
            val address: Long,
            val handle: Int,
        ) : Event

        data class GattNotify(
            val address: Long,
            val handle: Int,
        ) : Event

        class GattNotifyData(
            val address: Long,
            val handle: Int,
            val data: ByteArray,
        ) : Event

        data class GattError(
            val address: Long,
            val handle: Int,
            val error: Int,
        ) : Event
    }

    private enum class ConnectionState {
        CONNECTING,
        CONNECTED,
        DISCONNECTING,
    }

    private enum class BondAction {
        PAIR,
        UNPAIR,
    }

    private class ClearCacheSession(
        val gatt: BluetoothGatt,
        var completed: Boolean = false,
        var timeout: Runnable? = null,
    )

    private sealed interface PendingOperation {
        val errorHandle: Int

        data object DiscoverServices : PendingOperation {
            override val errorHandle: Int = 0
        }

        data class ReadCharacteristic(
            val handle: Int,
            val characteristic: BluetoothGattCharacteristic,
        ) : PendingOperation {
            override val errorHandle: Int = handle
        }

        data class ReadDescriptor(
            val handle: Int,
            val descriptor: BluetoothGattDescriptor,
        ) : PendingOperation {
            override val errorHandle: Int = handle
        }

        class WriteCharacteristic(
            val handle: Int,
            val characteristic: BluetoothGattCharacteristic,
            val data: ByteArray,
            val withResponse: Boolean,
        ) : PendingOperation {
            override val errorHandle: Int = handle
        }

        class WriteDescriptor(
            val handle: Int,
            val descriptor: BluetoothGattDescriptor,
            val data: ByteArray,
        ) : PendingOperation {
            override val errorHandle: Int = handle
        }

        class Notify(
            val handle: Int,
            val characteristic: BluetoothGattCharacteristic,
            val clientConfigDescriptor: BluetoothGattDescriptor,
            val enable: Boolean,
        ) : PendingOperation {
            override val errorHandle: Int = handle
        }
    }

    private class Connection(
        val address: Long,
        val macAddress: String,
        val gatt: BluetoothGatt,
        var state: ConnectionState,
        var mtu: Int,
    ) {
        var servicesLoaded: Boolean = false
        var nextDescriptorHandle: Int = SYNTHETIC_DESCRIPTOR_HANDLE_BASE
        val services: MutableList<EspHomeProtoCodec.GattService> = mutableListOf()
        val characteristicsByHandle: MutableMap<Int, BluetoothGattCharacteristic> = mutableMapOf()
        val descriptorsByHandle: MutableMap<Int, BluetoothGattDescriptor> = mutableMapOf()

        val pendingOperations: ArrayDeque<PendingOperation> = ArrayDeque()
        var inFlightOperation: PendingOperation? = null
    }

    private val bluetoothManager: BluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager.adapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private val connections = linkedMapOf<Long, Connection>()
    private val pendingBondActions = mutableMapOf<Long, BondAction>()
    private val pendingBondTimeouts = mutableMapOf<Long, Runnable>()
    private val pendingClearCacheSessions = mutableMapOf<Long, ClearCacheSession>()
    private var bondReceiverRegistered = false

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                return
            }

            val device: BluetoothDevice = intent.getBluetoothDeviceExtraCompat(BluetoothDevice.EXTRA_DEVICE) ?: return
            val mac = device.address ?: return
            val address = ProxyIdentity.macToLong(mac)
            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            val previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)

            runOnMain {
                onBondStateChanged(address, bondState, previousBondState)
            }
        }
    }

    @Volatile
    private var allocatedSnapshot: List<Long> = emptyList()

    fun connectionLimit(): Int = maxConnections

    fun allocatedConnections(): List<Long> = allocatedSnapshot

    fun handleDeviceRequest(request: EspHomeProtoCodec.DeviceRequest) {
        runOnMain {
            when (request.requestType) {
                EspHomeProtoCodec.BluetoothDeviceRequestType.CONNECT,
                EspHomeProtoCodec.BluetoothDeviceRequestType.CONNECT_V3_WITH_CACHE,
                EspHomeProtoCodec.BluetoothDeviceRequestType.CONNECT_V3_WITHOUT_CACHE,
                -> connectDevice(request.address)

                EspHomeProtoCodec.BluetoothDeviceRequestType.DISCONNECT -> disconnectDevice(request.address)

                EspHomeProtoCodec.BluetoothDeviceRequestType.PAIR -> handlePairRequest(request.address)
                EspHomeProtoCodec.BluetoothDeviceRequestType.UNPAIR -> handleUnpairRequest(request.address)
                EspHomeProtoCodec.BluetoothDeviceRequestType.CLEAR_CACHE -> handleClearCacheRequest(request.address)
            }
        }
    }

    fun handleGetServices(address: Long) {
        runOnMain {
            val connection = connections[address]
            if (connection == null || connection.state != ConnectionState.CONNECTED) {
                emit(Event.GattError(address = address, handle = 0, error = ERROR_NOT_CONNECTED))
                return@runOnMain
            }

            if (connection.servicesLoaded) {
                emitServices(connection)
                return@runOnMain
            }

            enqueueOperation(connection, PendingOperation.DiscoverServices)
        }
    }

    fun handleRead(request: EspHomeProtoCodec.AddressHandle) {
        runOnMain {
            val connection = connections[request.address]
            if (connection == null || connection.state != ConnectionState.CONNECTED) {
                emit(Event.GattError(address = request.address, handle = request.handle, error = ERROR_NOT_CONNECTED))
                return@runOnMain
            }

            if (!ensureServiceCache(connection)) {
                return@runOnMain
            }
            val characteristic = connection.characteristicsByHandle[request.handle]
            if (characteristic == null) {
                emit(Event.GattError(address = request.address, handle = request.handle, error = ERROR_INVALID_HANDLE))
                return@runOnMain
            }

            enqueueOperation(connection, PendingOperation.ReadCharacteristic(request.handle, characteristic))
        }
    }

    fun handleWrite(request: EspHomeProtoCodec.GattWriteRequest) {
        runOnMain {
            val connection = connections[request.address]
            if (connection == null || connection.state != ConnectionState.CONNECTED) {
                emit(Event.GattError(address = request.address, handle = request.handle, error = ERROR_NOT_CONNECTED))
                return@runOnMain
            }

            if (!ensureServiceCache(connection)) {
                return@runOnMain
            }
            val characteristic = connection.characteristicsByHandle[request.handle]
            if (characteristic == null) {
                emit(Event.GattError(address = request.address, handle = request.handle, error = ERROR_INVALID_HANDLE))
                return@runOnMain
            }

            enqueueOperation(
                connection,
                PendingOperation.WriteCharacteristic(
                    handle = request.handle,
                    characteristic = characteristic,
                    data = request.data,
                    withResponse = request.response,
                ),
            )
        }
    }

    fun handleReadDescriptor(request: EspHomeProtoCodec.AddressHandle) {
        runOnMain {
            val connection = connections[request.address]
            if (connection == null || connection.state != ConnectionState.CONNECTED) {
                emit(Event.GattError(address = request.address, handle = request.handle, error = ERROR_NOT_CONNECTED))
                return@runOnMain
            }

            if (!ensureServiceCache(connection)) {
                return@runOnMain
            }
            val descriptor = connection.descriptorsByHandle[request.handle]
            if (descriptor == null) {
                emit(Event.GattError(address = request.address, handle = request.handle, error = ERROR_INVALID_HANDLE))
                return@runOnMain
            }

            enqueueOperation(connection, PendingOperation.ReadDescriptor(request.handle, descriptor))
        }
    }

    fun handleWriteDescriptor(request: EspHomeProtoCodec.GattWriteDescriptorRequest) {
        runOnMain {
            val connection = connections[request.address]
            if (connection == null || connection.state != ConnectionState.CONNECTED) {
                emit(Event.GattError(address = request.address, handle = request.handle, error = ERROR_NOT_CONNECTED))
                return@runOnMain
            }

            if (!ensureServiceCache(connection)) {
                return@runOnMain
            }
            val descriptor = connection.descriptorsByHandle[request.handle]
            if (descriptor == null) {
                emit(Event.GattError(address = request.address, handle = request.handle, error = ERROR_INVALID_HANDLE))
                return@runOnMain
            }

            enqueueOperation(
                connection,
                PendingOperation.WriteDescriptor(
                    handle = request.handle,
                    descriptor = descriptor,
                    data = request.data,
                ),
            )
        }
    }

    fun handleNotify(request: EspHomeProtoCodec.GattNotifyRequest) {
        runOnMain {
            val connection = connections[request.address]
            if (connection == null || connection.state != ConnectionState.CONNECTED) {
                emit(Event.GattError(address = request.address, handle = request.handle, error = ERROR_NOT_CONNECTED))
                return@runOnMain
            }

            if (!ensureServiceCache(connection)) {
                return@runOnMain
            }
            val characteristic = connection.characteristicsByHandle[request.handle]
            if (characteristic == null) {
                emit(Event.GattError(address = request.address, handle = request.handle, error = ERROR_INVALID_HANDLE))
                return@runOnMain
            }

            val clientConfigDescriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            if (clientConfigDescriptor == null) {
                emit(Event.GattError(address = request.address, handle = request.handle, error = ERROR_INVALID_HANDLE))
                return@runOnMain
            }

            enqueueOperation(
                connection,
                PendingOperation.Notify(
                    handle = request.handle,
                    characteristic = characteristic,
                    clientConfigDescriptor = clientConfigDescriptor,
                    enable = request.enable,
                ),
            )
        }
    }

    private fun handlePairRequest(address: Long) {
        if (!hasConnectPermission()) {
            emit(Event.DevicePairing(address = address, paired = false, error = ERROR_PERMISSION_DENIED))
            return
        }

        val localAdapter = adapter
        if (localAdapter == null || !localAdapter.isEnabled) {
            emit(Event.DevicePairing(address = address, paired = false, error = ERROR_NOT_CONNECTED))
            return
        }

        val device = resolveDevice(localAdapter, address) ?: run {
            emit(Event.DevicePairing(address = address, paired = false, error = ERROR_INVALID_HANDLE))
            return
        }

        val bondState = try {
            device.bondState
        } catch (securityException: SecurityException) {
            emit(Event.DevicePairing(address = address, paired = false, error = ERROR_PERMISSION_DENIED))
            onError("Pair request permission denied for ${device.address}: ${securityException.message}")
            return
        }

        if (bondState == BluetoothDevice.BOND_BONDED) {
            emit(Event.DevicePairing(address = address, paired = true, error = 0))
            return
        }

        registerBondReceiverIfNeeded()
        clearBondTimeout(address)
        pendingBondActions[address] = BondAction.PAIR
        scheduleBondTimeout(address, BondAction.PAIR)

        if (bondState == BluetoothDevice.BOND_BONDING) {
            return
        }

        val started = try {
            device.createBond()
        } catch (securityException: SecurityException) {
            emit(Event.DevicePairing(address = address, paired = false, error = ERROR_PERMISSION_DENIED))
            onError("Pair request permission denied for ${device.address}: ${securityException.message}")
            return
        } catch (throwable: Throwable) {
            onError("Pair request failed to start for ${device.address}: ${throwable.message}")
            false
        }

        if (!started) {
            pendingBondActions.remove(address)
            clearBondTimeout(address)
            emit(Event.DevicePairing(address = address, paired = false, error = ERROR_OPERATION_FAILED))
        }
    }

    private fun handleUnpairRequest(address: Long) {
        if (!hasConnectPermission()) {
            emit(Event.DeviceUnpairing(address = address, success = false, error = ERROR_PERMISSION_DENIED))
            return
        }

        val localAdapter = adapter
        if (localAdapter == null || !localAdapter.isEnabled) {
            emit(Event.DeviceUnpairing(address = address, success = false, error = ERROR_NOT_CONNECTED))
            return
        }

        val device = resolveDevice(localAdapter, address) ?: run {
            emit(Event.DeviceUnpairing(address = address, success = false, error = ERROR_INVALID_HANDLE))
            return
        }

        val bondState = try {
            device.bondState
        } catch (securityException: SecurityException) {
            emit(Event.DeviceUnpairing(address = address, success = false, error = ERROR_PERMISSION_DENIED))
            onError("Unpair request permission denied for ${device.address}: ${securityException.message}")
            return
        }

        if (bondState == BluetoothDevice.BOND_NONE) {
            emit(Event.DeviceUnpairing(address = address, success = true, error = 0))
            return
        }

        registerBondReceiverIfNeeded()
        clearBondTimeout(address)
        pendingBondActions[address] = BondAction.UNPAIR
        scheduleBondTimeout(address, BondAction.UNPAIR)

        val started = runCatching {
            val removeBond = device.javaClass.getMethod("removeBond")
            removeBond.invoke(device) as? Boolean ?: false
        }.getOrElse {
            onError("Unpair request failed to start for ${device.address}: ${it.message}")
            false
        }

        if (!started) {
            pendingBondActions.remove(address)
            clearBondTimeout(address)
            emit(Event.DeviceUnpairing(address = address, success = false, error = ERROR_OPERATION_FAILED))
        }
    }

    private fun handleClearCacheRequest(address: Long) {
        if (!hasConnectPermission()) {
            emit(Event.DeviceClearCache(address = address, success = false, error = ERROR_PERMISSION_DENIED))
            return
        }

        val localAdapter = adapter
        if (localAdapter == null || !localAdapter.isEnabled) {
            emit(Event.DeviceClearCache(address = address, success = false, error = ERROR_NOT_CONNECTED))
            return
        }

        val existing = connections[address]
        if (existing != null) {
            val refreshed = invokeRefresh(existing.gatt)
            emit(
                Event.DeviceClearCache(
                    address = address,
                    success = refreshed,
                    error = if (refreshed) 0 else ERROR_OPERATION_FAILED,
                )
            )
            return
        }

        if (pendingClearCacheSessions.containsKey(address)) {
            emit(Event.DeviceClearCache(address = address, success = false, error = ERROR_BUSY))
            return
        }

        val device = resolveDevice(localAdapter, address) ?: run {
            emit(Event.DeviceClearCache(address = address, success = false, error = ERROR_INVALID_HANDLE))
            return
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                runOnMain {
                    val session = pendingClearCacheSessions[address]
                    if (session == null || session.gatt != gatt) {
                        closeGattQuietly(gatt, "orphan clear-cache callback")
                        return@runOnMain
                    }

                    if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        val refreshed = invokeRefresh(gatt)
                        session.completed = true
                        emit(
                            Event.DeviceClearCache(
                                address = address,
                                success = refreshed,
                                error = if (refreshed) 0 else ERROR_OPERATION_FAILED,
                            )
                        )
                        disconnectGattQuietly(gatt, "clear-cache $address")
                        return@runOnMain
                    }

                    if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        finishClearCacheSession(
                            address = address,
                            success = session.completed,
                            error = if (session.completed || status == BluetoothGatt.GATT_SUCCESS) 0 else status,
                        )
                    }
                }
            }
        }

        val gatt = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (securityException: SecurityException) {
            emit(Event.DeviceClearCache(address = address, success = false, error = ERROR_PERMISSION_DENIED))
            onError("Clear cache connection permission denied for ${device.address}: ${securityException.message}")
            null
        } catch (throwable: Throwable) {
            onError("Clear cache connection failed for ${device.address}: ${throwable.message}")
            null
        }

        if (gatt == null) {
            emit(Event.DeviceClearCache(address = address, success = false, error = ERROR_OPERATION_FAILED))
            return
        }

        val session = ClearCacheSession(gatt = gatt)
        val timeout = Runnable {
            runOnMain {
                val pending = pendingClearCacheSessions[address] ?: return@runOnMain
                if (pending.completed) {
                    return@runOnMain
                }
                finishClearCacheSession(address = address, success = false, error = ERROR_OPERATION_FAILED)
            }
        }
        session.timeout = timeout
        pendingClearCacheSessions[address] = session
        mainHandler.postDelayed(timeout, CLEAR_CACHE_TIMEOUT_MS)
    }

    fun stop() {
        runOnMain {
            val existing = connections.values.toList()
            connections.clear()
            updateAllocatedSnapshot()

            existing.forEach { connection ->
                disconnectGattQuietly(connection.gatt, connection.macAddress)
                closeGattQuietly(connection.gatt, connection.macAddress)
            }

            pendingBondActions.clear()
            pendingBondTimeouts.values.forEach(mainHandler::removeCallbacks)
            pendingBondTimeouts.clear()

            val cacheSessions = pendingClearCacheSessions.values.toList()
            pendingClearCacheSessions.clear()
            cacheSessions.forEach { session ->
                session.timeout?.let(mainHandler::removeCallbacks)
                disconnectGattQuietly(session.gatt, "clear-cache")
                closeGattQuietly(session.gatt, "clear-cache")
            }

            unregisterBondReceiverIfNeeded()

            emitConnectionsChanged()
        }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun emit(event: Event) {
        onEvent(event)
    }

    private fun emitConnectionsChanged() {
        val allocated = allocatedSnapshot
        emit(
            Event.ConnectionsChanged(
                free = (maxConnections - allocated.size).coerceAtLeast(0),
                limit = maxConnections,
                allocated = allocated,
            )
        )
    }

    private fun updateAllocatedSnapshot() {
        allocatedSnapshot = connections.keys.toList()
    }

    private fun ensureServiceCache(connection: Connection): Boolean {
        if (connection.servicesLoaded) {
            return true
        }

        val services = getGattServicesOrNull(connection) ?: run {
            emit(Event.GattError(address = connection.address, handle = 0, error = ERROR_PERMISSION_DENIED))
            return false
        }

        if (services.isNotEmpty()) {
            rebuildServiceCache(connection, services)
            connection.servicesLoaded = true
        }
        return true
    }

    private fun emitServices(connection: Connection) {
        if (connection.services.isNotEmpty()) {
            connection.services.forEach { service ->
                emit(
                    Event.GattServices(
                        address = connection.address,
                        services = listOf(service),
                    )
                )
            }
        }

        emit(Event.GattServicesDone(connection.address))
    }

    private fun enqueueOperation(connection: Connection, operation: PendingOperation) {
        connection.pendingOperations.addLast(operation)
        pumpOperationQueue(connection)
    }

    private fun pumpOperationQueue(connection: Connection) {
        if (connection.inFlightOperation != null) {
            return
        }

        val operation = if (connection.pendingOperations.isEmpty()) {
            null
        } else {
            connection.pendingOperations.removeFirst()
        } ?: return
        connection.inFlightOperation = operation

        if (!hasConnectPermission()) {
            failOperation(connection, operation.errorHandle, ERROR_PERMISSION_DENIED)
            return
        }

        val started = try {
            when (operation) {
                PendingOperation.DiscoverServices -> connection.gatt.discoverServices()

                is PendingOperation.ReadCharacteristic -> connection.gatt.readCharacteristic(operation.characteristic)

                is PendingOperation.ReadDescriptor -> connection.gatt.readDescriptor(operation.descriptor)

                is PendingOperation.WriteCharacteristic -> {
                    val writeType = if (operation.withResponse) {
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    } else {
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    }
                    writeCharacteristicCompat(
                        gatt = connection.gatt,
                        characteristic = operation.characteristic,
                        data = operation.data,
                        writeType = writeType,
                    )
                }

                is PendingOperation.WriteDescriptor -> {
                    writeDescriptorCompat(
                        gatt = connection.gatt,
                        descriptor = operation.descriptor,
                        data = operation.data,
                    )
                }

                is PendingOperation.Notify -> {
                    val subscribed = connection.gatt.setCharacteristicNotification(
                        operation.characteristic,
                        operation.enable,
                    )
                    if (!subscribed) {
                        false
                    } else {
                        val value = if (operation.enable) {
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        } else {
                            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                        }
                        writeDescriptorCompat(
                            gatt = connection.gatt,
                            descriptor = operation.clientConfigDescriptor,
                            data = value,
                        )
                    }
                }
            }
        } catch (securityException: SecurityException) {
            failOperation(connection, operation.errorHandle, ERROR_PERMISSION_DENIED)
            onError("GATT operation permission denied for ${connection.macAddress}: ${securityException.message}")
            return
        } catch (throwable: Throwable) {
            onError("GATT operation failed to start for ${connection.macAddress}: ${throwable.message}")
            false
        }

        if (!started) {
            failOperation(connection, operation.errorHandle, ERROR_OPERATION_FAILED)
            return
        }

        if (operation is PendingOperation.WriteCharacteristic && !operation.withResponse) {
            emit(Event.GattWrite(connection.address, operation.handle))
            completeOperation(connection)
        }
    }

    private fun completeOperation(connection: Connection) {
        connection.inFlightOperation = null
        pumpOperationQueue(connection)
    }

    private fun failOperation(connection: Connection, handle: Int, error: Int) {
        connection.inFlightOperation = null
        emit(Event.GattError(connection.address, handle, error))
        pumpOperationQueue(connection)
    }

    private fun connectDevice(address: Long) {
        if (!hasConnectPermission()) {
            emit(Event.DeviceConnection(address = address, connected = false, mtu = 0, error = ERROR_PERMISSION_DENIED))
            return
        }

        val localAdapter = adapter
        if (localAdapter == null || !localAdapter.isEnabled) {
            emit(Event.DeviceConnection(address = address, connected = false, mtu = 0, error = ERROR_NOT_CONNECTED))
            return
        }

        val existing = connections[address]
        if (existing != null) {
            if (existing.state == ConnectionState.CONNECTED) {
                emit(Event.DeviceConnection(address = address, connected = true, mtu = existing.mtu, error = 0))
            }
            return
        }

        if (connections.size >= maxConnections) {
            emit(Event.DeviceConnection(address = address, connected = false, mtu = 0, error = ERROR_NO_RESOURCES))
            return
        }

        val macAddress = ProxyIdentity.longToMac(address)
        val device = runCatching { localAdapter.getRemoteDevice(macAddress) }
            .getOrElse {
                emit(Event.DeviceConnection(address = address, connected = false, mtu = 0, error = ERROR_INVALID_HANDLE))
                return
            }

        val callback = createGattCallback(address)
        val gatt = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (securityException: SecurityException) {
            emit(Event.DeviceConnection(address = address, connected = false, mtu = 0, error = ERROR_PERMISSION_DENIED))
            onError("Connection permission denied for $macAddress: ${securityException.message}")
            return
        } catch (throwable: Throwable) {
            onError("Failed to connect to $macAddress: ${throwable.message}")
            emit(Event.DeviceConnection(address = address, connected = false, mtu = 0, error = ERROR_OPERATION_FAILED))
            return
        }

        if (gatt == null) {
            emit(Event.DeviceConnection(address = address, connected = false, mtu = 0, error = ERROR_OPERATION_FAILED))
            return
        }

        connections[address] = Connection(
            address = address,
            macAddress = macAddress,
            gatt = gatt,
            state = ConnectionState.CONNECTING,
            mtu = DEFAULT_MTU,
        )
        updateAllocatedSnapshot()
        emitConnectionsChanged()
    }

    private fun disconnectDevice(address: Long) {
        val connection = connections[address]
        if (connection == null) {
            emit(Event.DeviceConnection(address = address, connected = false, mtu = 0, error = ERROR_NOT_CONNECTED))
            return
        }
        if (!hasConnectPermission()) {
            closeConnection(connection, ERROR_PERMISSION_DENIED)
            return
        }

        connection.state = ConnectionState.DISCONNECTING
        val disconnected = runCatching {
            disconnectGattQuietly(connection.gatt, connection.macAddress)
        }.getOrElse {
            onError("Failed to disconnect ${connection.macAddress}: ${it.message}")
            false
        }
        if (!disconnected) {
            closeConnection(connection, ERROR_OPERATION_FAILED)
        }
    }

    private fun closeConnection(connection: Connection, error: Int) {
        connections.remove(connection.address)
        updateAllocatedSnapshot()

        closeGattQuietly(connection.gatt, connection.macAddress)

        connection.inFlightOperation = null
        connection.pendingOperations.clear()

        emit(Event.DeviceConnection(address = connection.address, connected = false, mtu = 0, error = error))
        emitConnectionsChanged()
    }

    private fun rebuildServiceCache(connection: Connection, services: List<BluetoothGattService>) {
        connection.services.clear()
        connection.characteristicsByHandle.clear()
        connection.descriptorsByHandle.clear()
        connection.nextDescriptorHandle = SYNTHETIC_DESCRIPTOR_HANDLE_BASE

        for (service in services) {
            val characteristics = service.characteristics.map { characteristic ->
                val descriptors = characteristic.descriptors.map { descriptor ->
                    val descriptorHandle = connection.nextDescriptorHandle++
                    connection.descriptorsByHandle[descriptorHandle] = descriptor
                    EspHomeProtoCodec.GattDescriptor(
                        uuid = descriptor.uuid,
                        handle = descriptorHandle,
                    )
                }

                val characteristicHandle = characteristic.instanceId
                connection.characteristicsByHandle[characteristicHandle] = characteristic

                EspHomeProtoCodec.GattCharacteristic(
                    uuid = characteristic.uuid,
                    handle = characteristicHandle,
                    properties = characteristic.properties,
                    descriptors = descriptors,
                )
            }

            connection.services += EspHomeProtoCodec.GattService(
                uuid = service.uuid,
                handle = service.instanceId,
                characteristics = characteristics,
            )
        }
    }

    private fun createGattCallback(address: Long): BluetoothGattCallback {
        return object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                runOnMain {
                    val connection = connections[address]
                    if (connection == null || connection.gatt != gatt) {
                        closeGattQuietly(gatt, "orphan connection callback")
                        return@runOnMain
                    }

                    if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        connection.state = ConnectionState.CONNECTED
                        emit(Event.DeviceConnection(address = address, connected = true, mtu = connection.mtu, error = 0))
                        if (!hasConnectPermission()) {
                            closeConnection(connection, ERROR_PERMISSION_DENIED)
                            return@runOnMain
                        }
                        try {
                            gatt.requestMtu(REQUESTED_MTU)
                        } catch (securityException: SecurityException) {
                            onError("MTU request permission denied for ${connection.macAddress}: ${securityException.message}")
                            closeConnection(connection, ERROR_PERMISSION_DENIED)
                        }
                        return@runOnMain
                    }

                    if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        val error = if (status == BluetoothGatt.GATT_SUCCESS) 0 else status
                        closeConnection(connection, error)
                        return@runOnMain
                    }

                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        closeConnection(connection, status)
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                runOnMain {
                    val connection = connections[address] ?: return@runOnMain
                    if (connection.gatt != gatt) {
                        return@runOnMain
                    }
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        connection.mtu = mtu
                        emit(Event.DeviceConnection(address = address, connected = true, mtu = mtu, error = 0))
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                runOnMain {
                    val connection = connections[address] ?: return@runOnMain
                    if (connection.gatt != gatt) {
                        return@runOnMain
                    }

                    val inFlight = connection.inFlightOperation
                    if (inFlight !is PendingOperation.DiscoverServices) {
                        return@runOnMain
                    }

                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        failOperation(connection, 0, status)
                        return@runOnMain
                    }

                    val services = getGattServicesOrNull(connection) ?: run {
                        failOperation(connection, 0, ERROR_PERMISSION_DENIED)
                        return@runOnMain
                    }
                    rebuildServiceCache(connection, services)
                    connection.servicesLoaded = true
                    emitServices(connection)
                    completeOperation(connection)
                }
            }

            @Deprecated("Used for API 24-32 callback compatibility.")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                handleCharacteristicRead(gatt, characteristic, characteristicValueLegacy(characteristic), status)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                handleCharacteristicRead(gatt, characteristic, value, status)
            }

            @Deprecated("Used for API 24-32 callback compatibility.")
            override fun onDescriptorRead(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                handleDescriptorRead(gatt, descriptor, descriptorValueLegacy(descriptor), status)
            }

            override fun onDescriptorRead(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
                value: ByteArray,
            ) {
                handleDescriptorRead(gatt, descriptor, value, status)
            }

            @Suppress("UNUSED_PARAMETER")
            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                ignoredCharacteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                runOnMain {
                    val connection = connections[address] ?: return@runOnMain
                    if (connection.gatt != gatt) {
                        return@runOnMain
                    }

                    val inFlight = connection.inFlightOperation as? PendingOperation.WriteCharacteristic ?: return@runOnMain
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        emit(Event.GattWrite(address = address, handle = inFlight.handle))
                        completeOperation(connection)
                    } else {
                        failOperation(connection, inFlight.handle, status)
                    }
                }
            }

            @Suppress("UNUSED_PARAMETER")
            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                ignoredDescriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                runOnMain {
                    val connection = connections[address] ?: return@runOnMain
                    if (connection.gatt != gatt) {
                        return@runOnMain
                    }

                    when (val inFlight = connection.inFlightOperation) {
                        is PendingOperation.WriteDescriptor -> {
                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                emit(Event.GattWrite(address = address, handle = inFlight.handle))
                                completeOperation(connection)
                            } else {
                                failOperation(connection, inFlight.handle, status)
                            }
                        }

                        is PendingOperation.Notify -> {
                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                emit(Event.GattNotify(address = address, handle = inFlight.handle))
                                completeOperation(connection)
                            } else {
                                failOperation(connection, inFlight.handle, status)
                            }
                        }

                        else -> Unit
                    }
                }
            }

            @Deprecated("Used for API 24-32 callback compatibility.")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                handleCharacteristicChanged(gatt, characteristic, characteristicValueLegacy(characteristic))
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                handleCharacteristicChanged(gatt, characteristic, value)
            }

            private fun handleCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                runOnMain {
                    val connection = connections[address] ?: return@runOnMain
                    if (connection.gatt != gatt) {
                        return@runOnMain
                    }

                    val inFlight = connection.inFlightOperation as? PendingOperation.ReadCharacteristic ?: return@runOnMain
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        emit(
                            Event.GattRead(
                                address = address,
                                handle = inFlight.handle,
                                data = value.copyOf(),
                            )
                        )
                        completeOperation(connection)
                    } else {
                        failOperation(connection, inFlight.handle, status)
                    }
                }
            }

            private fun handleDescriptorRead(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                value: ByteArray,
                status: Int,
            ) {
                runOnMain {
                    val connection = connections[address] ?: return@runOnMain
                    if (connection.gatt != gatt) {
                        return@runOnMain
                    }

                    val inFlight = connection.inFlightOperation as? PendingOperation.ReadDescriptor ?: return@runOnMain
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        emit(
                            Event.GattRead(
                                address = address,
                                handle = inFlight.handle,
                                data = value.copyOf(),
                            )
                        )
                        completeOperation(connection)
                    } else {
                        failOperation(connection, inFlight.handle, status)
                    }
                }
            }

            private fun handleCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                runOnMain {
                    val connection = connections[address] ?: return@runOnMain
                    if (connection.gatt != gatt) {
                        return@runOnMain
                    }

                    emit(
                        Event.GattNotifyData(
                            address = address,
                            handle = characteristic.instanceId,
                            data = value.copyOf(),
                        )
                    )
                }
            }
        }
    }

    private fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun resolveDevice(adapter: BluetoothAdapter, address: Long): BluetoothDevice? {
        if (!hasConnectPermission()) {
            return null
        }
        val macAddress = ProxyIdentity.longToMac(address)
        return runCatching { adapter.getRemoteDevice(macAddress) }.getOrNull()
    }

    private fun Intent.getBluetoothDeviceExtraCompat(name: String): BluetoothDevice? {
        return IntentCompat.getParcelableExtra(this, name, BluetoothDevice::class.java)
    }

    private fun registerBondReceiverIfNeeded() {
        if (bondReceiverRegistered) {
            return
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        ContextCompat.registerReceiver(context, bondStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        bondReceiverRegistered = true
    }

    private fun unregisterBondReceiverIfNeeded() {
        if (!bondReceiverRegistered) {
            return
        }
        runCatching {
            context.unregisterReceiver(bondStateReceiver)
        }
        bondReceiverRegistered = false
    }

    private fun onBondStateChanged(address: Long, bondState: Int, previousBondState: Int) {
        val action = pendingBondActions[address] ?: return

        when (action) {
            BondAction.PAIR -> {
                if (bondState == BluetoothDevice.BOND_BONDED) {
                    pendingBondActions.remove(address)
                    clearBondTimeout(address)
                    emit(Event.DevicePairing(address = address, paired = true, error = 0))
                } else if (bondState == BluetoothDevice.BOND_NONE && previousBondState == BluetoothDevice.BOND_BONDING) {
                    pendingBondActions.remove(address)
                    clearBondTimeout(address)
                    emit(Event.DevicePairing(address = address, paired = false, error = ERROR_OPERATION_FAILED))
                }
            }

            BondAction.UNPAIR -> {
                if (bondState == BluetoothDevice.BOND_NONE) {
                    pendingBondActions.remove(address)
                    clearBondTimeout(address)
                    emit(Event.DeviceUnpairing(address = address, success = true, error = 0))
                } else if (bondState == BluetoothDevice.BOND_BONDED && previousBondState == BluetoothDevice.BOND_BONDED) {
                    pendingBondActions.remove(address)
                    clearBondTimeout(address)
                    emit(Event.DeviceUnpairing(address = address, success = false, error = ERROR_OPERATION_FAILED))
                }
            }
        }
    }

    private fun scheduleBondTimeout(address: Long, action: BondAction) {
        clearBondTimeout(address)
        val timeout = Runnable {
            runOnMain {
                if (pendingBondActions.remove(address) == null) {
                    return@runOnMain
                }
                clearBondTimeout(address)
                when (action) {
                    BondAction.PAIR -> emit(Event.DevicePairing(address = address, paired = false, error = ERROR_OPERATION_FAILED))
                    BondAction.UNPAIR -> emit(Event.DeviceUnpairing(address = address, success = false, error = ERROR_OPERATION_FAILED))
                }
            }
        }
        pendingBondTimeouts[address] = timeout
        mainHandler.postDelayed(timeout, BOND_TIMEOUT_MS)
    }

    private fun clearBondTimeout(address: Long) {
        pendingBondTimeouts.remove(address)?.let(mainHandler::removeCallbacks)
    }

    private fun invokeRefresh(gatt: BluetoothGatt): Boolean {
        return runCatching {
            val refreshMethod = gatt.javaClass.getMethod("refresh")
            refreshMethod.invoke(gatt) as? Boolean ?: false
        }.getOrElse {
            onError("BluetoothGatt.refresh() failed: ${it.message}")
            false
        }
    }

    private fun finishClearCacheSession(address: Long, success: Boolean, error: Int) {
        val session = pendingClearCacheSessions.remove(address) ?: return
        session.timeout?.let(mainHandler::removeCallbacks)

        disconnectGattQuietly(session.gatt, "clear-cache $address")
        closeGattQuietly(session.gatt, "clear-cache $address")

        if (!session.completed) {
            emit(Event.DeviceClearCache(address = address, success = success, error = error))
        }
    }

    private fun getGattServicesOrNull(connection: Connection): List<BluetoothGattService>? {
        if (!hasConnectPermission()) {
            return null
        }
        return try {
            connection.gatt.services
        } catch (securityException: SecurityException) {
            onError("Permission denied while reading services for ${connection.macAddress}: ${securityException.message}")
            null
        }
    }

    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
        writeType: Int,
    ): Boolean {
        if (!hasConnectPermission()) {
            return false
        }
        characteristic.writeType = writeType
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                gatt.writeCharacteristic(characteristic, data, writeType) == BluetoothStatusCodes.SUCCESS
            } catch (securityException: SecurityException) {
                onError("Permission denied while writing characteristic: ${securityException.message}")
                false
            }
        } else {
            writeCharacteristicLegacy(gatt, characteristic, data)
        }
    }

    private fun writeCharacteristicLegacy(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
    ): Boolean {
        val setValueMethod = BluetoothGattCharacteristic::class.java.getMethod("setValue", ByteArray::class.java)
        val setValueOk = setValueMethod.invoke(characteristic, data) as? Boolean ?: false
        if (!setValueOk) {
            return false
        }
        val writeMethod = BluetoothGatt::class.java.getMethod(
            "writeCharacteristic",
            BluetoothGattCharacteristic::class.java,
        )
        return writeMethod.invoke(gatt, characteristic) as? Boolean ?: false
    }

    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        data: ByteArray,
    ): Boolean {
        if (!hasConnectPermission()) {
            return false
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                gatt.writeDescriptor(descriptor, data) == BluetoothStatusCodes.SUCCESS
            } catch (securityException: SecurityException) {
                onError("Permission denied while writing descriptor: ${securityException.message}")
                false
            }
        } else {
            writeDescriptorLegacy(gatt, descriptor, data)
        }
    }

    private fun writeDescriptorLegacy(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        data: ByteArray,
    ): Boolean {
        val setValueMethod = BluetoothGattDescriptor::class.java.getMethod("setValue", ByteArray::class.java)
        val setValueOk = setValueMethod.invoke(descriptor, data) as? Boolean ?: false
        if (!setValueOk) {
            return false
        }
        val writeMethod = BluetoothGatt::class.java.getMethod(
            "writeDescriptor",
            BluetoothGattDescriptor::class.java,
        )
        return writeMethod.invoke(gatt, descriptor) as? Boolean ?: false
    }

    private fun characteristicValueLegacy(characteristic: BluetoothGattCharacteristic): ByteArray {
        val getValueMethod = BluetoothGattCharacteristic::class.java.getMethod("getValue")
        return (getValueMethod.invoke(characteristic) as? ByteArray)?.copyOf() ?: ByteArray(0)
    }

    private fun descriptorValueLegacy(descriptor: BluetoothGattDescriptor): ByteArray {
        val getValueMethod = BluetoothGattDescriptor::class.java.getMethod("getValue")
        return (getValueMethod.invoke(descriptor) as? ByteArray)?.copyOf() ?: ByteArray(0)
    }

    private fun disconnectGattQuietly(gatt: BluetoothGatt, label: String): Boolean {
        if (!hasConnectPermission()) {
            return false
        }
        return try {
            gatt.disconnect()
            true
        } catch (securityException: SecurityException) {
            onError("Permission denied while disconnecting $label: ${securityException.message}")
            false
        }
    }

    private fun closeGattQuietly(gatt: BluetoothGatt, label: String): Boolean {
        if (!hasConnectPermission()) {
            return false
        }
        return try {
            gatt.close()
            true
        } catch (securityException: SecurityException) {
            onError("Permission denied while closing $label: ${securityException.message}")
            false
        }
    }

    companion object {
        private const val DEFAULT_MAX_CONNECTIONS = 5
        private const val DEFAULT_MTU = 23
        private const val REQUESTED_MTU = 517
        private const val SYNTHETIC_DESCRIPTOR_HANDLE_BASE = 0x10000
        private const val BOND_TIMEOUT_MS = 15_000L
        private const val CLEAR_CACHE_TIMEOUT_MS = 12_000L

        private const val ERROR_NOT_CONNECTED = -1
        private const val ERROR_INVALID_HANDLE = 1
        private const val ERROR_NO_RESOURCES = -12
        private const val ERROR_PERMISSION_DENIED = -13
        private const val ERROR_BUSY = -16
        private const val ERROR_OPERATION_FAILED = -95

        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
