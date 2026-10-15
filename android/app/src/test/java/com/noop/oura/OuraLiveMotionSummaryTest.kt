package com.noop.oura

import org.junit.Assert.*
import org.junit.Test

class OuraLiveMotionSummaryTest {
    @Test fun reciprocalMalformedAndBoundaryOracleMatchesStandaloneSwift() {
        val zero = OuraLiveMotionSummary.sample(1000000, OuraRawXYZ(0, 0, 0))
        val base = zero.payloadJSON()
        val cases = listOf(
            "count" to "true",
            "count" to "\"1\"",
            "count" to "1.5",
            "count" to "1.0",
            "count" to "1e0",
            "count" to "null",
            "count" to "9223372036854775808",
            "count" to "9223372036854775807",
            "count" to "0",
            "count" to "-1",
            "sum_x" to "9223372036854775807",
            "sum_x" to "-9223372036854775808",
            "min_x" to "-32769",
            "max_x" to "32768",
            "min_x" to "1",
            "sum_x" to "1",
            "first_receipt_ms" to "1000001",
            "last_receipt_ms" to "1001000",
            "first_receipt_ms" to "-1"
        )
        val actual = mutableListOf<String>()
        fun status(label: String, payload: String) {
            actual += "$label:${if (com.noop.data.OuraLiveMotionHistory.parse(1000, payload) == null) "error" else "ok"}"
        }
        for ((key, token) in cases) {
            status("$key=$token", base.replace(Regex("\"$key\":-?[0-9]+"), "\"$key\":$token"))
        }
        val maxValues = zero.values + ("count" to 281474976710655L)
        val maximum = OuraLiveMotionSummary(maxValues)
        status("valid", base)
        status("spaced", " \n" + base.replace(",", ",\n ") + "\n\t")
        status("duplicate", base.replace("{", "{\"count\":1,"))
        status("unknown", base.replace("{", "{\"extra\":0,"))
        status("missing", base.replace("\"count\":1,", ""))
        status("maximum", maximum.payloadJSON())
        status("maximumNegative", OuraLiveMotionSummary(maxValues + mapOf("sum_x" to -9223372036854743040L, "min_x" to -32768L, "max_x" to -32768L)).payloadJSON())
        actual += "boundaryMerged:${OuraLiveMotionSummary(maxValues + ("count" to 281474976710654L)).merge(zero).values.getValue("count")}"
        actual += "boundaryRejected:${if (runCatching { maximum.merge(zero) }.isFailure) "error" else "ok"}"
        actual += "constructedRejected:${if (runCatching { OuraLiveMotionSummary(maxValues + ("count" to Long.MAX_VALUE)).merge(zero) }.isFailure) "error" else "ok"}"
        actual += "missingRejected:${if (runCatching { OuraLiveMotionSummary(emptyMap()).merge(zero) }.isFailure) "error" else "ok"}"
        actual += "recovered:${zero.merge(zero).values.getValue("count")}"
        assertEquals("""
count=true:error
count="1":error
count=1.5:error
count=1.0:error
count=1e0:error
count=null:error
count=9223372036854775808:error
count=9223372036854775807:error
count=0:error
count=-1:error
sum_x=9223372036854775807:error
sum_x=-9223372036854775808:error
min_x=-32769:error
max_x=32768:error
min_x=1:error
sum_x=1:error
first_receipt_ms=1000001:error
last_receipt_ms=1001000:error
first_receipt_ms=-1:error
valid:ok
spaced:ok
duplicate:error
unknown:error
missing:error
maximum:ok
maximumNegative:ok
boundaryMerged:281474976710655
boundaryRejected:error
constructedRejected:error
missingRejected:error
recovered:2
""".trimIndent(), actual.joinToString("\n"))
    }
    @Test fun persistedPayloadRejectsCoercionsImpossibleValuesAndInconsistentBounds() {
        val base = OuraLiveMotionSummary.sample(1000000, OuraRawXYZ(0, 0, 0)).payloadJSON()
        val cases = listOf(
            "count" to "true", "count" to "\"1\"", "count" to "1.5", "count" to "1.0", "count" to "1e0",
            "count" to "9223372036854775808", "count" to "9223372036854775807", "count" to "0",
            "sum_x" to "9223372036854775807", "sum_x" to "-9223372036854775808",
            "min_x" to "-32769", "max_x" to "32768", "min_x" to "1", "sum_x" to "1",
            "first_receipt_ms" to "1000001", "last_receipt_ms" to "1001000", "first_receipt_ms" to "-1"
        )
        for ((key, token) in cases) {
            val bad = base.replace(Regex("\"$key\":-?[0-9]+"), "\"$key\":$token")
            assertNull("$key=$token", com.noop.data.OuraLiveMotionHistory.parse(1000, bad))
        }
        assertNull(com.noop.data.OuraLiveMotionHistory.parse(1000, base.replace("{", "{\"extra\":0,")))
        assertNotNull(com.noop.data.OuraLiveMotionHistory.parse(1000, base))
    }
    @Test fun mergeRejectsArithmeticBoundaryRecoverablyWithoutMutatingInputs() {
        val zero = OuraLiveMotionSummary.sample(1000000, OuraRawXYZ(0, 0, 0))
        val largest = zero.copy(values = zero.values + ("count" to 281474976710655L))
        val before = largest.payloadJSON()
        assertThrows(IllegalArgumentException::class.java) { largest.merge(zero) }
        assertEquals(before, largest.payloadJSON())
    }
    @Test fun standaloneSwiftSummaryOraclePinsCompleteSignedInputAndSpread() {
        val expected = listOf(
            """{"count":257,"first_receipt_ms":1000000,"last_receipt_ms":1000256,"max_x":256,"max_y":4352,"max_z":7943,"min_x":0,"min_y":0,"min_z":7,"provenance":"host_receipt","sum_x":32896,"sum_y":559232,"sum_z":1021575,"units":"raw_signed16"}""",
            """{"count":65536,"first_receipt_ms":1000000,"last_receipt_ms":1000999,"max_x":32767,"max_y":32767,"max_z":32767,"min_x":-32768,"min_y":-32768,"min_z":-32768,"provenance":"host_receipt","sum_x":-32768,"sum_y":-32768,"sum_z":-32768,"units":"raw_signed16"}"""
        )
        for ((index, count) in listOf(257, 65536).withIndex()) {
            var summary: OuraLiveMotionSummary? = null
            for (bits in 0 until count) {
                val next = OuraLiveMotionSummary.sample(1000000L + bits % 1000,
                    OuraRawXYZ(bits.toShort().toInt(), (bits * 17).toShort().toInt(), (bits * 31 + 7).toShort().toInt()))
                summary = summary?.merge(next) ?: next
            }
            assertEquals(expected[index], summary!!.payloadJSON())
        }
    }
    @Test fun mergePreservesCountsPeaksAndReceptionOrderAcrossPartialSessions() {
        val a = OuraLiveMotionSummary.sample(1000123, OuraRawXYZ(-32768, 4, 32767))
        val b = OuraLiveMotionSummary.sample(1000789, OuraRawXYZ(32767, -8, -32768))
        val merged = b.merge(a)
        assertEquals(2L, merged.values.getValue("count"))
        assertEquals(-1L, merged.values.getValue("sum_x"))
        assertEquals(-32768L, merged.values.getValue("min_x"))
        assertEquals(32767L, merged.values.getValue("max_x"))
        assertEquals(-0.5, merged.mean("x"), 0.0)
        assertEquals(a.merge(b).payloadJSON(), merged.payloadJSON())
    }
}
