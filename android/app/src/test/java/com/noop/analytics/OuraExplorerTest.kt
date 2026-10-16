package com.noop.analytics

import org.junit.Assert.*
import org.junit.Test

/** Tests numerical display contracts; no Bluetooth or scoring calculations are changed. */
class OuraExplorerTest {
    @Test fun latestMeansOriginalObservationNotMedianAndSurvivesEmptyAppend() {
        val summary = SignalDecimation.reduce(listOf(SignalDecimation.Sample(19, 100.0),
            SignalDecimation.Sample(1, 20.0), SignalDecimation.Sample(10, 30.0)), 0, 30, 30)
        assertEquals(30.0, summary.buckets.single().median, 0.0)
        assertEquals(100.0, summary.latestValue!!, 0.0)
        assertEquals(100.0, summary.append(SignalDecimation.Summary()).latestValue!!, 0.0)
    }

    @Test fun fourSelectionsAreRetainedWhenAFifthIsAttemptedAndCanBeRemoved() {
        var selected = emptyList<String>()
        for (id in listOf("hr", "temperature", "movement", "oxygen", "battery")) selected = OuraComparison.toggle(selected, id)
        assertEquals(listOf("hr", "temperature", "movement", "oxygen"), selected)
        assertEquals(listOf("hr", "movement", "oxygen"), OuraComparison.toggle(selected, "temperature"))
    }

    @Test fun compatibleAxesShareDomainsButHeartAndTemperatureDoNot() {
        val traces = listOf(trace("x", "accel", "raw", -10.0, 10.0), trace("y", "accel", "raw", -20.0, 5.0),
            trace("hr", "heart", "bpm", 50.0, 80.0), trace("temp", "temperature", "°C", 30.0, 35.0))
        val axes = OuraComparison.axes(traces)
        assertEquals(3, axes.size)
        assertEquals(axes.getValue("accel|raw"), OuraComparison.axisFor(traces[1], axes))
        assertTrue(axes.getValue("accel|raw").minimum < -20.0)
        assertTrue(axes.getValue("accel|raw").maximum > 10.0)
        assertNotEquals(OuraComparison.axisFor(traces[2], axes), OuraComparison.axisFor(traces[3], axes))
    }

    @Test fun sameUnitWithDifferentMeaningDoesNotShareScaleAndFlatValuesStayFinite() {
        val traces = listOf(trace("absolute", "temperature", "°C", 30.0, 30.0),
            trace("relative", "temperature_delta", "°C", 0.0, 0.0))
        val axes = OuraComparison.axes(traces)
        assertEquals(2, axes.size)
        axes.values.forEach { assertTrue(it.maximum > it.minimum); assertTrue(it.minimum.isFinite()) }
    }

    @Test fun fahrenheitAxisUsesConvertedOriginalExtrema() {
        val original = SignalDecimation.reduce(listOf(SignalDecimation.Sample(1, 32.5), SignalDecimation.Sample(2, 35.0)), 0, 10, 10)
        val trace = OuraComparison.Trace("temp", "temperature", "°F", original, 1.8, 32.0)
        val axis = OuraComparison.axes(listOf(trace)).getValue("temperature|°F")
        assertEquals(90.0, axis.minimum, 0.00001)
        assertEquals(95.5, axis.maximum, 0.00001)
    }

    @Test fun extremeFiniteInputCannotCreateAnInfiniteOrCollapsedAxis() {
        val axes = OuraComparison.axes(listOf(trace("x", "raw", "raw", -1e308, 1e308),
            trace("temp", "temperature", "°F", 1e308, 1e308).copy(scale = 1.8, offset = 32.0)))
        axes.values.forEach {
            assertTrue(it.minimum.isFinite() && it.maximum.isFinite())
            assertTrue((it.maximum - it.minimum).isFinite())
            assertTrue(it.maximum > it.minimum)
        }
        assertTrue(axes.isEmpty())
    }

    @Test fun cursorUsesActualContainingBucketAndLeavesMissingTimeMissing() {
        val buckets = listOf(SignalDecimation.Bucket(0, 10, 60.0, 50.0, 70.0, 2),
            SignalDecimation.Bucket(20, 30, 90.0, 90.0, 90.0, 1))
        assertEquals(60.0, OuraComparison.bucketAt(buckets, 9)!!.median, 0.0)
        assertNull(OuraComparison.bucketAt(buckets, 10))
        assertNull(OuraComparison.bucketAt(buckets, 19))
        assertNull(OuraComparison.bucketAt(buckets, 30))
    }

    private fun trace(id: String, dimension: String, unit: String, min: Double, max: Double) = OuraComparison.Trace(
        id, dimension, unit, SignalDecimation.reduce(listOf(SignalDecimation.Sample(1, min),
            SignalDecimation.Sample(2, max)), 0, 10, 10))
}
