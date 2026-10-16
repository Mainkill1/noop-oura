package com.noop.ui

import android.content.Context
import android.util.Base64
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.platform.app.InstrumentationRegistry
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import com.noop.ble.OuraInstallKeyStore
import com.noop.ingest.OuraKeyXml
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Real AndroidX/Keystore interoperability and app_process helper checks use only synthetic keys. */
class OuraKeyTransferDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun actualAndroidXFileAndStandaloneHelperPreserveSyntheticKey() {
        // Device-protected storage isolates this fixture from the app's real credential preferences.
        val deviceStorage = context.createDeviceProtectedStorageContext()
        val fixtureContext = object : android.content.ContextWrapper(deviceStorage) {
            override fun getApplicationContext(): Context = this
        }
        OuraInstallKeyStore.clear(context, "oura-transfer-fixture")
        val name = "noop_oura_secure_prefs"
        val key = IntArray(16) { 255 - it * 7 }
        val fixture = File(fixtureContext.dataDir, "shared_prefs/$name.xml")
        fixtureContext.deleteSharedPreferences(name)
        try {
            val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(fixtureContext, name, master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            assertTrue(prefs.edit().putString("install_key_oura-transfer-fixture",
                Base64.encodeToString(ByteArray(16) { key[it].toByte() }, Base64.NO_WRAP))
                .putBoolean("adopt_intent_oura-transfer-fixture", true).commit())
            val aead = AndroidKeystoreKmsClient().getAead("android-keystore://_androidx_security_master_key_")
            val parsed = fixture.inputStream().use { OuraKeyXml.read(it, aead) }
            assertArrayEquals(key, parsed.single().key)
            assertEquals("oura-transfer-fixture", parsed.single().deviceId)
            val exporter = context.packageManager.getApplicationInfo("com.noop.oura.keyexporter", 0)
            val process = ProcessBuilder("/system/bin/app_process", "/system/bin", "com.noop.keyexporter.RootKeyExportMain",
                fixture.absolutePath, android.os.Process.myUid().toString()).apply {
                environment()["CLASSPATH"] = exporter.sourceDir
            }.start()
            val timeout = Thread { if (!process.waitFor(30, TimeUnit.SECONDS)) process.destroyForcibly() }.apply { start() }
            try {
                val bytes = process.inputStream.use { it.readBytes() }
                assertTrue(process.waitFor(30, TimeUnit.SECONDS))
                val diagnostics = process.errorStream.bufferedReader().use { it.readText().take(1024) }
                assertEquals(diagnostics, 0, process.exitValue())
                val exported = OuraKeyXml.read(bytes.inputStream())
                assertArrayEquals(key, exported.single().key)
            } finally { process.destroyForcibly(); timeout.join() }
        } finally { fixtureContext.deleteSharedPreferences(name) }
    }

    @Test fun restoringKeysClearsAdoptionIntentAndRoundTripsThroughSecureStorage() {
        val id = "oura-transfer-storage-fixture"
        val key = IntArray(16) { it * 11 }
        try {
            OuraInstallKeyStore.setPendingAdopt(context, id, true)
            assertTrue(OuraInstallKeyStore.importKeys(context, listOf(OuraKeyXml.Entry(id, key))))
            assertArrayEquals(key, OuraInstallKeyStore.load(context, id))
            assertFalse(OuraInstallKeyStore.consumePendingAdopt(context, id))
            val entries = OuraInstallKeyStore.exportKeys(context).filter { it.deviceId == id }
            assertArrayEquals(key, OuraKeyXml.read(OuraKeyXml.write(entries).byteInputStream()).single().key)
            assertFalse(OuraInstallKeyStore.importKeys(context, listOf(OuraKeyXml.Entry(id, IntArray(3)))))
            assertArrayEquals(key, OuraInstallKeyStore.load(context, id))
        } finally {
            if (InstrumentationRegistry.getArguments().getString("keepRootExportFixture") != "true")
                OuraInstallKeyStore.clear(context, id)
        }
    }

    @Test fun transferDialogExplainsImportAndExportAndDoesNotExposeCredentials() {
        compose.setContent { NoopTheme { Surface(color = Palette.surfaceBase) { OuraKeyTransferDialog {} } } }
        compose.onNodeWithText("Transfer ring keys").assertIsDisplayed()
        compose.onNodeWithText("Import key XML").assertIsDisplayed()
        compose.onNodeWithText("Export ring keys").performScrollTo().assertIsDisplayed()
    }
}
