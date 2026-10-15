package com.noop.oura

import org.junit.Assert.*
import org.junit.Test

class OuraLiveMotionSummaryTest {
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
