package com.noop.oura

import org.junit.Assert.*
import org.junit.Test

class OuraRawInstrumentsTest {
    private fun wire(tag: Int, payload: List<Int>) = listOf(tag, payload.size + 4, 1, 0, 0, 0) + payload
    @Test fun completeEnvelopeOnlyAndAuthenticationNeverEntersCapture() {
        assertTrue(OuraRawInstruments.capture(listOf(0x2f, 4, 0x04, 1, 2, 3)).isEmpty())
        assertTrue(OuraRawInstruments.capture(listOf(0x43, 5, 1, 0, 0, 0, 65)).isEmpty())
        assertTrue(OuraRawInstruments.capture(wire(0x80, listOf(100, 8)) + listOf(0x33, 8, 0)).isEmpty())
        assertEquals("event:90", OuraRawInstruments.capture(wire(0x90, listOf(1, 255))).single().channel)
        assertTrue(OuraRawInstruments.capture(listOf(0x2f, 6, 0x21, 99, 0, 0, 0, 0)).isEmpty())
    }
    @Test fun qualityAndZeroPairsSurviveProductionFiltering() {
        val raw = OuraRawInstruments.capture(wire(0x80, listOf(0, 0, 100, 8, 255, 31))).single()
        assertEquals(listOf(0.0, 800.0, 2047.0), raw.fields.first { it.key == "ibi" }.values)
        assertEquals(listOf(0.0, 1.0, 3.0), raw.fields.first { it.key == "quality" }.values)
        val night = OuraRawInstruments.capture(wire(0x60, List(14) { 0 })).single()
        assertEquals(List(6) { 0.0 }, night.fields.first { it.key == "ibi" }.values)
        val hrv = OuraRawInstruments.capture(wire(0x5d, listOf(0, 0, 255, 255))).single()
        assertEquals(listOf(0.0, 255.0), hrv.fields.first { it.key == "rmssd" }.values)
    }
    @Test fun researchBytesDoNotBecomeOxygenOrReconstructedPpg() {
        for (tag in listOf(0x81, 0x8b)) {
            val raw = OuraRawInstruments.capture(wire(tag, listOf(0x80, 255, 255, 255, 0))).single()
            assertTrue(raw.fields.all { it.key.startsWith("byte[") })
            assertEquals(wire(tag, listOf(0x80, 255, 255, 255, 0)), raw.wire)
        }
        assertTrue(OuraRawInstruments.catalog.map { it.key }.containsAll(OuraEventTag.entries.map { "event:%02x".format(it.raw) }))
    }
    @Test fun malformedSensorRetainsBytesWithoutInventingDecodedValues() {
        val raw = OuraRawInstruments.capture(wire(0x46, listOf(1))).single()
        assertEquals(listOf("byte[0]"), raw.fields.map { it.key })
        assertEquals(1L, raw.ringTick)
        assertEquals("\\x00\\x0a\\x5cA", OuraRawInstruments.readable(listOf(0, 10, 92, 65)))
    }
    @Test fun boundedWindowIsSnapshotAndReportsEviction() {
        val memory = OuraRawBuffer(maxRecords = 2, maxBytes = 40, maxValues = 40)
        val raw = OuraRawInstruments.capture(wire(0x80, listOf(100, 8))).single()
        memory.append(raw, 100)
        val before = memory.snapshot()
        memory.append(raw, 200); memory.append(raw, 300)
        assertEquals(1, before.records.size)
        assertEquals(2, memory.snapshot().records.size)
        assertEquals(1L, memory.snapshot().evictedRecords)
        assertEquals(8L, memory.snapshot().evictedBytes)
    }
    @Test fun secureCaptureAllowlistExcludesEveryOtherSuboperationAndFeature() {
        for (subop in 0..255) {
            val capture = OuraRawInstruments.capture(listOf(0x2f, 8, subop, 2, 0, 0, 0, 0, 0, 0))
            assertEquals("subop=$subop", subop in setOf(0x21, 0x28), capture.isNotEmpty())
        }
        for (feature in 0..255) {
            val captured = OuraRawInstruments.capture(listOf(0x2f, 6, 0x21, feature, 0, 0, 0, 0))
            assertEquals("feature=$feature", feature in setOf(2, 4, 11), captured.isNotEmpty())
        }
        assertTrue(OuraRawInstruments.capture(listOf(0x19, 3, 65, 66, 67)).isEmpty())
    }
    @Test fun byteAndNumericBudgetsEvictWholeRecordsAndNeverMutateEarlierSnapshot() {
        val raw = OuraRawInstruments.capture(wire(0x80, listOf(100, 8))).single()
        val bytes = OuraRawBuffer(maxRecords = 99, maxBytes = 8, maxValues = 40)
        bytes.append(raw, 1); bytes.append(raw, 2)
        assertEquals(1, bytes.snapshot().records.size)
        assertEquals(1L, bytes.snapshot().evictedRecords)
        val numeric = OuraRawBuffer(maxRecords = 99, maxBytes = 99, maxValues = 4)
        numeric.append(raw, 1); numeric.append(raw, 2)
        assertEquals(4L, numeric.snapshot().evictedValues)
        val oversized = OuraRawBuffer(maxBytes = 7)
        oversized.append(raw, 1)
        assertEquals(1L, oversized.snapshot().truncatedRecords)
        assertTrue(oversized.snapshot().records.isEmpty())
    }

}
