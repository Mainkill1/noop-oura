package com.noop.ui

import com.noop.ble.*
import org.junit.Assert.*
import org.junit.Test

class OuraLivePlotModelTest {
    @Test fun heldMotionViewportClampsAfterCountEvictionWithoutDiscardingAnotherSelectedSignal() {
        val capped = (40001L..46000L).map { OuraLivePoint(it, 0.0) }
        assertEquals(40001L, ouraLiveAvailableFrom(listOf(capped), 60000))
        assertEquals(20000L, ouraLiveAvailableFrom(listOf(capped, listOf(OuraLivePoint(20000, 60.0))), 60000))
        assertEquals(0L, ouraLiveAvailableFrom(listOf(listOf(OuraLivePoint(50000, 60.0))), 60000))
    }

    @Test fun stoppedWindowFreezesAtLastActualReceiptAndOtherRingHasNoSamples() {
        val stopped = OuraLiveSessionSnapshot(sourceDeviceId = "a", hr = OuraLiveStream(
            samples = listOf(OuraLiveHrSample(12345, 68, null)), lastReceiptElapsedMs = 12345))
        assertEquals(12345L, ouraLivePlotEnd(stopped, 99999))
        assertEquals(12345L, ouraLivePlotEnd(stopped, 199999))
        assertTrue(ouraLiveSnapshotForSource(stopped, "b").hr.samples.isEmpty())
        assertEquals(99999L, ouraLivePlotEnd(stopped.copy(active = true), 99999))
    }

    @Test fun streamAgesAreIndependentAndMonotonic() {
        assertEquals(7L, ouraLiveAgeSeconds(1000, 8999))
        assertEquals(0L, ouraLiveAgeSeconds(9000, 8999))
        assertNull(ouraLiveAgeSeconds(null, 8999))
        assertEquals(OuraLiveReadingState.Stale, ouraLiveReadingState(true, true, OuraLiveStreamPhase.RECEIVING, 1000, 8000, 5000))
        assertEquals(OuraLiveReadingState.Stopped, ouraLiveReadingState(false, true, OuraLiveStreamPhase.IDLE, 1000, 8000, 5000))
        assertEquals(OuraLiveReadingState.Disconnected, ouraLiveReadingState(true, false, OuraLiveStreamPhase.RECEIVING, 1000, 8000, 5000))
    }

    @Test fun reductionKeepsPositiveAndNegativePeaksAndDoesNotJoinGap() {
        val samples = listOf(OuraLivePoint(0, 1.0), OuraLivePoint(1, 900.0), OuraLivePoint(2, -700.0),
            OuraLivePoint(3, 2.0), OuraLivePoint(10000, 3.0), OuraLivePoint(10001, 4.0))
        val segments = ouraLiveSegments(samples, 0, 11000, 1, 1500)
        assertEquals(2, segments.size)
        assertTrue(segments[0].contains(OuraLivePoint(1, 900.0)))
        assertTrue(segments[0].contains(OuraLivePoint(2, -700.0)))
        assertEquals(listOf(10000L, 10001L), segments[1].map { it.elapsedMs })
    }

    @Test fun trendUsesShortTrailingWindowAndNeverBridgesMissingReceipts() {
        val raw = listOf(OuraLivePoint(0, 60.0), OuraLivePoint(1000, 90.0), OuraLivePoint(2000, 90.0), OuraLivePoint(10000, 120.0))
        assertEquals(listOf(60.0, 75.0, 80.0, 120.0), ouraLiveHrTrend(raw).map { it.value })
        assertEquals(listOf(60.0, 90.0, 90.0, 120.0), raw.map { it.value })
    }

    @Test fun inspectionDoesNotInventValueInsideGapAndKeepsDuplicateReceiptVectors() {
        val points = listOf(OuraLivePoint(0, 1.0), OuraLivePoint(0, 9.0), OuraLivePoint(10000, 3.0))
        assertEquals(listOf(1.0, 9.0), ouraLiveInspect(points, 0, 1500).map { it.value })
        assertTrue(ouraLiveInspect(points, 5000, 1500).isEmpty())
    }

    @Test fun motionSmoothingUses250msReceiptWindowPreservesVectorsGapsAndRawInspection() {
        val raw = listOf(OuraLivePoint(0, -100.0), OuraLivePoint(0, 100.0),
            OuraLivePoint(100, 300.0), OuraLivePoint(251, 500.0), OuraLivePoint(2000, -700.0))
        assertEquals(listOf(-100.0, 0.0, 100.0, 400.0, -700.0), ouraLiveMotionTrend(raw).map { it.value })
        assertEquals(listOf(-100.0, 100.0), ouraLiveInspect(raw, 0, 1500).map { it.value })
        val burst = (0 until 6000).map { OuraLivePoint(it * 10L, if (it % 2 == 0) 32767.0 else -32768.0) }
        val projection = ouraLiveSegments(ouraLiveMotionTrend(burst), 0, 60000, 100, 1500).flatten()
        assertTrue(projection.size <= 404)
        assertEquals(6000, burst.size)
    }
}
