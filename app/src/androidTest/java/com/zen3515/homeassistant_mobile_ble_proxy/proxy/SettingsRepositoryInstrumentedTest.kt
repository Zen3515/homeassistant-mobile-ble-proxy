package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryInstrumentedTest {
    @Test
    fun bleAdvTogglePersistsAcrossRepositoryRecreation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = SettingsRepository(context)
        val original = repository.settings.first()

        try {
            repository.save(original.copy(bleAdvProxyEnabled = true))
            val afterEnabledRestart = SettingsRepository(context).settings.first()
            assertTrue(afterEnabledRestart.bleAdvProxyEnabled)

            repository.save(original.copy(bleAdvProxyEnabled = false))
            val afterDisabledRestart = SettingsRepository(context).settings.first()
            assertFalse(afterDisabledRestart.bleAdvProxyEnabled)
        } finally {
            repository.save(original)
        }
    }
}
