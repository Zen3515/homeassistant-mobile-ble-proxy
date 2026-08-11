package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import android.Manifest
import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePermissionPolicyTest {
    @Test
    fun `android 12 plain proxy does not request advertise permission`() {
        val permissions = RuntimePermissionPolicy.requiredPermissions(
            sdkInt = Build.VERSION_CODES.S,
            bleAdvProxyEnabled = false,
        )

        assertTrue(Manifest.permission.BLUETOOTH_SCAN in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in permissions)
        assertFalse(Manifest.permission.BLUETOOTH_ADVERTISE in permissions)
    }

    @Test
    fun `android 12 requests advertise permission only after ble adv is enabled`() {
        val permissions = RuntimePermissionPolicy.requiredPermissions(
            sdkInt = Build.VERSION_CODES.S,
            bleAdvProxyEnabled = true,
        )

        assertTrue(Manifest.permission.BLUETOOTH_ADVERTISE in permissions)
    }

    @Test
    fun `pre android 12 never requests advertise runtime permission`() {
        val permissions = RuntimePermissionPolicy.requiredPermissions(
            sdkInt = Build.VERSION_CODES.R,
            bleAdvProxyEnabled = true,
        )

        assertFalse(Manifest.permission.BLUETOOTH_ADVERTISE in permissions)
    }
}
