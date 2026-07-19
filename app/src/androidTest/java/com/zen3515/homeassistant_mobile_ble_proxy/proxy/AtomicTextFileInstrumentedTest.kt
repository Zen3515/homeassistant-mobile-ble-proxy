package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AtomicTextFileInstrumentedTest {
    @Test
    fun reportRoundTripsInNoBackupStorageAndCanBeCleared() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(
            context.noBackupFilesDir,
            "crash-diagnostics-test-${UUID.randomUUID()}.txt",
        )
        val store = AtomicTextFile(file = file, maxBytes = 1024)

        try {
            store.write("production crash report")

            assertTrue(file.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
            assertEquals("production crash report", store.read())

            store.delete()
            assertFalse(file.exists())
            assertNull(store.read())
        } finally {
            store.delete()
        }
    }

    @Test
    fun interruptedAtomicBackupIsRecoveredWhenBaseFileIsMissing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(
            context.noBackupFilesDir,
            "crash-diagnostics-recovery-${UUID.randomUUID()}.txt",
        )
        val backupFile = File("${file.path}.bak")
        val store = AtomicTextFile(file = file, maxBytes = 1024)

        try {
            backupFile.writeText("recovered crash report")

            assertEquals("recovered crash report", store.read())
        } finally {
            store.delete()
            backupFile.delete()
        }
    }
}
