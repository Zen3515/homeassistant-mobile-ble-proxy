package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import android.bluetooth.BluetoothGatt

/** Applies exact BLE link parameters when the Android platform can support them. */
interface BluetoothConnectionParamsController {
    val supportsExactParameters: Boolean

    fun apply(
        gatt: BluetoothGatt,
        request: EspHomeProtoCodec.ConnectionParamsRequest,
    ): Int
}

object UnsupportedBluetoothConnectionParamsController : BluetoothConnectionParamsController {
    override val supportsExactParameters: Boolean = false

    override fun apply(
        gatt: BluetoothGatt,
        request: EspHomeProtoCodec.ConnectionParamsRequest,
    ): Int = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
}
