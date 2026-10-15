package com.noop.oura

import org.junit.Assert.*
import org.junit.Test

class OuraLiveMotionTest {
    // Literal, synthetic wire fixture: signed LE boundaries, never a historical ring timestamp.
    @Test fun decodesTwoRawVectorsWithoutCalibration() {
        val frame = OuraOuterFrame(0x33, intArrayOf(0x31, 0xfe, 0, 0x80, 0xff, 0x7f, 0xff, 0xff, 0, 0, 1, 0, 0x34, 0x12))
        val decoded = OuraLiveMotion.decode(frame)!!
        assertEquals(0x31, decoded.metadataByte)
        assertEquals(254, decoded.sequence)
        assertEquals(listOf(OuraRawXYZ(-32768, 32767, -1), OuraRawXYZ(0, 1, 4660)), decoded.samples)
    }
    @Test fun acceptsOneVectorAndPreservesOpaqueMetadata() {
        val packet = OuraLiveMotion.decode(OuraOuterFrame(0x33, intArrayOf(0, 255, 1, 0, 2, 0, 3, 0)))!!
        assertEquals(0, packet.metadataByte)
        assertEquals(listOf(OuraRawXYZ(1, 2, 3)), packet.samples)
    }
    @Test fun commandsHaveBoundedStartAndDocumentedOff() {
        assertArrayEquals(intArrayOf(6,7,32,0,0,0,1,0,0), OuraCommands.liveMotionStart().bytes)
        assertArrayEquals(intArrayOf(6,4,0,0,0,0), OuraCommands.liveMotionStop().bytes)
    }
    @Test fun rejectsTruncatedUnknownOrHistoricalFrames() {
        assertNull(OuraLiveMotion.decode(OuraOuterFrame(0x47, IntArray(14))))
        assertNull(OuraLiveMotion.decode(OuraOuterFrame(0x33, IntArray(13))))
        assertNull(OuraLiveMotion.decode(OuraOuterFrame(0x33, IntArray(15))))
        assertNull(OuraLiveMotion.decode(OuraOuterFrame(0x33, IntArray(9))))
        assertNull(OuraLiveMotion.decode(OuraOuterFrame(0x33, intArrayOf(0x31, -1) + IntArray(12))))
    }
    @Test fun swiftOracleCoversEverySigned16Value() {
        // Verbatim stdout from swiftc -O Framing.swift OuraLiveMotion.swift main.swift.
        val expected = """0:0,21930,-21931
1:1,21930,-21931
127:127,21930,-21931
128:128,21930,-21931
255:255,21930,-21931
256:256,21930,-21931
32767:32767,21930,-21931
32768:-32768,21930,-21931
65534:-2,21930,-21931
65535:-1,21930,-21931
65536:-32768:-11729197793280:-32768:32767"""
        val lines = mutableListOf<String>()
        var total = 0L; var weighted = 0L; var min = Int.MAX_VALUE; var max = Int.MIN_VALUE
        for (bits in 0..65535) {
            val v = OuraLiveMotion.decode(OuraOuterFrame(0x33,
                intArrayOf(77, bits and 255, bits and 255, bits shr 8, 0xaa, 0x55, 0x55, 0xaa)))!!.samples[0]
            total += v.x; weighted += (bits + 1L) * v.x
            min = minOf(min, v.x); max = maxOf(max, v.x)
            if (bits in listOf(0,1,127,128,255,256,32767,32768,65534,65535)) lines.add("$bits:${v.x},${v.y},${v.z}")
        }
        lines.add("65536:$total:$weighted:$min:$max")
        assertEquals(expected, lines.joinToString("\n"))
    }

}
