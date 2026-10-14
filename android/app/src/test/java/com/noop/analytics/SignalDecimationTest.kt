package com.noop.analytics

import org.junit.Assert.*
import org.junit.Test

class SignalDecimationTest {
    @Test fun mediansPreserveExtremaAndOriginalSampleWeightedMean() {
        val result = SignalDecimation.reduce(listOf(
            SignalDecimation.Sample(5, 60.0), SignalDecimation.Sample(6, 70.0),
            SignalDecimation.Sample(7, 200.0), SignalDecimation.Sample(15, 90.0),
        ), 0, 30, 10)
        assertEquals(listOf(70.0, 90.0), result.buckets.map { it.median })
        assertEquals(listOf(3, 1), result.buckets.map { it.count })
        assertEquals(105.0, result.mean!!, 0.0)
        assertEquals(60.0, result.minimum!!, 0.0)
        assertEquals(200.0, result.maximum!!, 0.0)
        assertEquals(15L, result.latestTs)
    }

    @Test fun boundariesGapsAndNonfiniteSamplesAreNotInventedReadings() {
        val result = SignalDecimation.reduce(listOf(
            SignalDecimation.Sample(-1, 99.0), SignalDecimation.Sample(0, 10.0),
            SignalDecimation.Sample(1, 20.0), SignalDecimation.Sample(10, Double.NaN),
            SignalDecimation.Sample(11, Double.POSITIVE_INFINITY),
            SignalDecimation.Sample(29, 40.0), SignalDecimation.Sample(30, 99.0),
        ), 0, 30, 10)
        assertEquals(listOf(0L, 20L), result.buckets.map { it.start })
        assertEquals(listOf(15.0, 40.0), result.buckets.map { it.median })
        assertEquals(3, result.count)
        assertEquals(30L, result.buckets.last().end)
    }

    @Test fun alignedBatchesMatchOneWindowIncludingEmptyBatch() {
        val samples = listOf(SignalDecimation.Sample(1, 10.0), SignalDecimation.Sample(25, 50.0))
        val whole = SignalDecimation.reduce(samples, 0, 30, 10)
        val batched = SignalDecimation.reduce(samples, 0, 10, 10)
            .append(SignalDecimation.reduce(samples, 10, 20, 10))
            .append(SignalDecimation.reduce(samples, 20, 30, 10))
        assertEquals(whole, batched)
        assertNull(SignalDecimation.reduce(emptyList(), 0, 30, 10).mean)
    }
}
