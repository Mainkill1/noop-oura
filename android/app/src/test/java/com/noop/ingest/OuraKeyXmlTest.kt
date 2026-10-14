package com.noop.ingest

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class OuraKeyXmlTest {
    private val key = IntArray(16) { it * 15 }
    private val encoded = Base64.getEncoder().encodeToString(ByteArray(16) { key[it].toByte() })
    private fun read(xml: String) = OuraKeyXml.read(ByteArrayInputStream(xml.toByteArray()))

    @Test fun nativeBase64AndPortableRoundTripPreserveIdentityAndBytes() {
        val keys = read("""<map><string name="install_key_oura-AA:BB:CC:DD:EE:FF">$encoded</string><boolean name="adopt_intent_oura-AA:BB:CC:DD:EE:FF" value="true"/></map>""")
        assertEquals("oura-AA:BB:CC:DD:EE:FF", keys.single().deviceId)
        assertArrayEquals(key, keys.single().key)
        val xml = OuraKeyXml.write(keys)
        assertFalse(xml.contains("adopt_intent"))
        assertArrayEquals(key, read(xml).single().key)
    }

    @Test fun hexAndMultipleRingsAreAccepted() {
        val result = read("""<map><string name="install_key_oura-serial1">00112233445566778899aabbccddeeff</string><string name="install_key_oura-serial2">$encoded</string></map>""")
        assertEquals(2, result.size)
        assertEquals(255, result.first().key.last())
    }

    @Test fun malformedDuplicateAndWrongLengthFailWithoutReturningPartialKeys() {
        for (xml in listOf(
            """<map><string name="install_key_a">$encoded</string><string name="install_key_a">$encoded</string></map>""",
            """<map><string name="install_key_a">$encoded</string><string name="install_key_b">short</string></map>""",
            """<map><string name="install_key_a">$encoded</map>""",
            """<other><string name="install_key_a">$encoded</string></other>""",
            """<!DOCTYPE map [<!ENTITY secret SYSTEM "file:///etc/passwd">]><map><string name="install_key_a">&secret;</string></map>""",
        )) assertThrows(OuraKeyXml.Failure::class.java) { read(xml) }
    }

    @Test fun emptyUnrelatedAndOversizedFilesAreRejected() {
        assertThrows(OuraKeyXml.Failure::class.java) { read("<map><string name=\"unrelated\">value</string></map>") }
        assertThrows(OuraKeyXml.Failure::class.java) { read(" ".repeat(OuraKeyXml.MAX_BYTES + 1)) }
    }

    @Test fun androidXEncryptedEnvelopeDecryptsOnlyWithItsOriginalMasterKey() {
        com.google.crypto.tink.aead.AeadConfig.register()
        com.google.crypto.tink.daead.DeterministicAeadConfig.register()
        val master = com.google.crypto.tink.KeysetHandle.generateNew(com.google.crypto.tink.aead.AeadKeyTemplates.AES256_GCM)
            .getPrimitive(com.google.crypto.tink.Aead::class.java)
        val names = com.google.crypto.tink.KeysetHandle.generateNew(com.google.crypto.tink.daead.DeterministicAeadKeyTemplates.AES256_SIV)
        val values = com.google.crypto.tink.KeysetHandle.generateNew(com.google.crypto.tink.aead.AeadKeyTemplates.AES256_GCM)
        fun wrapped(handle: com.google.crypto.tink.KeysetHandle): String {
            val out = java.io.ByteArrayOutputStream()
            handle.write(com.google.crypto.tink.BinaryKeysetWriter.withOutputStream(out), master)
            return out.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        val name = Base64.getEncoder().encodeToString(names.getPrimitive(com.google.crypto.tink.DeterministicAead::class.java)
            .encryptDeterministically("install_key_oura-test".toByteArray(), "noop_oura_secure_prefs".toByteArray()))
        val buffer = java.nio.ByteBuffer.allocate(8 + encoded.length).putInt(0).putInt(encoded.length).put(encoded.toByteArray()).array()
        val value = Base64.getEncoder().encodeToString(values.getPrimitive(com.google.crypto.tink.Aead::class.java)
            .encrypt(buffer, name.toByteArray()))
        val xml = """<map><string name="__androidx_security_crypto_encrypted_prefs_key_keyset__">${wrapped(names)}</string><string name="__androidx_security_crypto_encrypted_prefs_value_keyset__">${wrapped(values)}</string><string name="$name">$value</string></map>"""
        assertArrayEquals(key, OuraKeyXml.read(xml.byteInputStream(), master).single().key)
        assertEquals(OuraKeyXml.Problem.ENCRYPTED_UNAVAILABLE,
            assertThrows(OuraKeyXml.Failure::class.java) { read(xml) }.problem)
        val wrong = com.google.crypto.tink.KeysetHandle.generateNew(com.google.crypto.tink.aead.AeadKeyTemplates.AES256_GCM)
            .getPrimitive(com.google.crypto.tink.Aead::class.java)
        assertEquals(OuraKeyXml.Problem.ENCRYPTED_UNAVAILABLE,
            assertThrows(OuraKeyXml.Failure::class.java) { OuraKeyXml.read(xml.byteInputStream(), wrong) }.problem)
    }

    @Test fun encryptedFileIsNotMistakenForPortableKeyMaterial() {
        val failure = assertThrows(OuraKeyXml.Failure::class.java) {
            read("""<map><string name="__androidx_security_crypto_encrypted_prefs_key_keyset__">00</string></map>""")
        }
        assertEquals(OuraKeyXml.Problem.ENCRYPTED_UNAVAILABLE, failure.problem)
        assertFalse(failure.toString().contains(encoded))
    }
}
