package com.noop.ingest

import android.util.Xml
import com.google.crypto.tink.Aead
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.DeterministicAead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.daead.DeterministicAeadConfig
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.StringWriter
import java.nio.ByteBuffer
import java.util.Base64

/** Bounded XML interchange for ring credentials. Never imports adoption intent or other preferences. */
object OuraKeyXml {
    const val MAX_BYTES = 131072
    const val FILE_NAME = "noop_oura_secure_prefs.xml"
    private const val PREFIX = "install_key_"
    private const val KEYSET = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
    private const val VALUESET = "__androidx_security_crypto_encrypted_prefs_value_keyset__"
    enum class Problem { INVALID, TOO_LARGE, NO_KEYS, ENCRYPTED_UNAVAILABLE }
    class Failure(val problem: Problem) : Exception(problem.name)
    class Entry(val deviceId: String, val key: IntArray) {
        override fun toString() = "Oura key entry (redacted)"
    }

    fun read(input: InputStream, master: Aead? = null): List<Entry> {
        val output = java.io.ByteArrayOutputStream()
        val block = ByteArray(4096)
        while (true) {
            val n = input.read(block)
            if (n < 0) break
            if (output.size() + n > MAX_BYTES) throw Failure(Problem.TOO_LARGE)
            output.write(block, 0, n)
        }
        val bytes = output.toByteArray()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            parser.setInput(bytes.inputStream(), null)
            val values = linkedMapOf<String, String>()
            val names = mutableSetOf<String>()
            var root = false
            var closed = false
            var name: String? = null
            var text = StringBuilder()
            while (true) {
                when (parser.nextToken()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.DOCDECL -> throw Failure(Problem.INVALID)
                    XmlPullParser.START_TAG -> {
                        if (parser.depth == 1) {
                            if (root || closed || parser.name != "map") throw Failure(Problem.INVALID)
                            root = true
                        } else if (parser.depth == 2 && root && !closed) {
                            val key = parser.getAttributeValue(null, "name") ?: throw Failure(Problem.INVALID)
                            if (!names.add(key)) throw Failure(Problem.INVALID)
                            name = if (parser.name == "string") key else null
                            text = StringBuilder()
                        } else throw Failure(Problem.INVALID)
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                        if (parser.eventType == XmlPullParser.ENTITY_REF && parser.text == null) throw Failure(Problem.INVALID)
                        if (name != null) text.append(parser.text)
                        else if (parser.depth <= 1 && !parser.text.isNullOrBlank()) throw Failure(Problem.INVALID)
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.depth == 2) {
                            name?.let { values[it] = text.toString() }
                            name = null
                        } else if (parser.depth == 1) closed = true
                    }
                }
            }
            if (!root || !closed) throw Failure(Problem.INVALID)
            val plain = if (values.containsKey(KEYSET) || values.containsKey(VALUESET)) {
                if (master == null) throw Failure(Problem.ENCRYPTED_UNAVAILABLE)
                decrypt(values, master)
            } else values
            val entries = plain.filterKeys { it.startsWith(PREFIX) }.map { (id, value) ->
                val deviceId = id.removePrefix(PREFIX)
                if (deviceId.isBlank() || deviceId.length > 200 || deviceId.any { it.isISOControl() }) throw Failure(Problem.INVALID)
                val trimmed = value.trim()
                val key = if (trimmed.matches(Regex("[0-9a-fA-F]{32}"))) {
                    ByteArray(16) { trimmed.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                } else Base64.getDecoder().decode(trimmed)
                if (key.size != 16) throw Failure(Problem.INVALID)
                Entry(deviceId, IntArray(16) { key[it].toInt() and 255 })
            }
            if (entries.isEmpty()) throw Failure(Problem.NO_KEYS)
            return entries
        } catch (e: Failure) { throw e }
        catch (_: Exception) { throw Failure(Problem.INVALID) }
    }

    // AndroidX preference envelopes bind names to the original filename and values to encrypted names.
    // Keysets use Tink's public reader; no keysets or master keys are created during import.
    private fun decrypt(values: Map<String, String>, master: Aead): Map<String, String> {
        try {
            AeadConfig.register()
            DeterministicAeadConfig.register()
            fun keyset(name: String) = KeysetHandle.read(BinaryKeysetReader.withBytes(hex(values.getValue(name))), master)
            val names = keyset(KEYSET).getPrimitive(DeterministicAead::class.java)
            val contents = keyset(VALUESET).getPrimitive(Aead::class.java)
            val result = linkedMapOf<String, String>()
            values.filterKeys { it != KEYSET && it != VALUESET }.forEach { (encryptedName, encryptedValue) ->
                val name = String(names.decryptDeterministically(Base64.getDecoder().decode(encryptedName),
                    "noop_oura_secure_prefs".toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
                if (name.startsWith(PREFIX)) {
                    val buffer = ByteBuffer.wrap(contents.decrypt(Base64.getDecoder().decode(encryptedValue), encryptedName.toByteArray(Charsets.UTF_8)))
                    if (buffer.int != 0) throw Failure(Problem.INVALID)
                    val length = buffer.int
                    if (length != buffer.remaining()) throw Failure(Problem.INVALID)
                    val value = ByteArray(length).also { buffer.get(it) }.toString(Charsets.UTF_8)
                    if (result.put(name, value) != null) throw Failure(Problem.INVALID)
                }
            }
            return result
        } catch (e: Failure) { throw e }
        catch (_: Exception) { throw Failure(Problem.ENCRYPTED_UNAVAILABLE) }
    }

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun write(entries: List<Entry>): String {
        require(entries.isNotEmpty() && entries.map { it.deviceId }.distinct().size == entries.size)
        val out = StringWriter()
        val xml = Xml.newSerializer()
        xml.setOutput(out)
        xml.startDocument("UTF-8", true)
        xml.startTag(null, "map")
        entries.forEach {
            require(it.key.size == 16 && it.key.all { byte -> byte in 0..255 })
            xml.startTag(null, "string").attribute(null, "name", PREFIX + it.deviceId)
                .text(Base64.getEncoder().encodeToString(ByteArray(16) { index -> it.key[index].toByte() }))
                .endTag(null, "string")
        }
        xml.endTag(null, "map")
        xml.endDocument()
        return out.toString()
    }
}
