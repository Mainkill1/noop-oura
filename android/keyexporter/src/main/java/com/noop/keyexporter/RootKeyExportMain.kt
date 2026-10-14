package com.noop.keyexporter

import android.system.Os
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import com.noop.ingest.OuraKeyXml
import java.io.File

/** Root helper drops to the source app UID before asking Android Keystore to decrypt its keysets. */
object RootKeyExportMain {
    @JvmStatic fun main(args: Array<String>) {
        var stage = "arguments"
        try {
            require(args.size == 2)
            val uid = args[1].toInt()
            require(uid >= 10000)
            // Read only this app's Oura preferences, while root can access its sandbox.
            stage = "read_preferences"
            val bytes = File(args[0]).inputStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val block = ByteArray(4096)
                while (true) {
                    val n = input.read(block)
                    if (n < 0) break
                    require(out.size() + n <= OuraKeyXml.MAX_BYTES)
                    out.write(block, 0, n)
                }
                out.toByteArray()
            }
            stage = "source_identity"
            if (Os.getuid() == 0) {
                Os.setgid(uid)
                Os.setuid(uid)
            } else require(Os.getuid() == uid)
            // app_process has no Zygote app preload, so register the platform Keystore provider explicitly.
            stage = "keystore_provider"
            if (java.security.Security.getProvider("AndroidKeyStore") == null) {
                val provider = if (android.os.Build.VERSION.SDK_INT >= 31)
                    "android.security.keystore2.AndroidKeyStoreProvider" else "android.security.keystore.AndroidKeyStoreProvider"
                Class.forName(provider).getMethod("install").invoke(null)
            }
            stage = "keystore_access"
            val master = AndroidKeystoreKmsClient().getAead("android-keystore://_androidx_security_master_key_")
            stage = "decode_preferences"
            val keys = OuraKeyXml.read(bytes.inputStream(), master)
            // Stdout is a private process pipe consumed by ExporterActivity; never log credentials.
            System.out.print(OuraKeyXml.write(keys))
            System.out.flush()
        } catch (error: Throwable) {
            System.err.print("Oura key export failed at " + stage + " (" + error.javaClass.simpleName + ")")
            kotlin.system.exitProcess(1)
        }
    }
}
