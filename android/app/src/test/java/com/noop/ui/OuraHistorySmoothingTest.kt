package com.noop.ui

import com.noop.analytics.SignalDecimation
import org.junit.Assert.*
import org.junit.Test

class OuraHistorySmoothingTest {
    private val buckets = listOf(
        SignalDecimation.Bucket(0, 300, 60.0, 40.0, 80.0, 2),
        SignalDecimation.Bucket(300, 600, 120.0, 120.0, 120.0, 1),
        SignalDecimation.Bucket(600, 900, 60.0, 50.0, 70.0, 8),
        SignalDecimation.Bucket(1500, 1800, 180.0, 160.0, 200.0, 3),
        SignalDecimation.Bucket(1800, 2100, 120.0, 100.0, 140.0, 2),
    )

    @Test fun offKeepsTheOriginalMedianTrace() {
        assertEquals(buckets.map { it.median }, ouraHistoryTrend(buckets, 0))
        assertEquals(buckets.map { it.median }, ouraHistoryTrend(buckets, -1))
    }

    @Test fun smoothingResetsAtMissingBucketsAndLeavesRawStatisticsIntact() {
        val before = buckets.toList()
        assertEquals(listOf(60.0, 90.0, 80.0, 180.0, 150.0), ouraHistoryTrend(buckets, 1))
        assertEquals(before, buckets)
    }

    @Test fun strongerSmoothingUsesTheSelectedWindowWithoutFillingGaps() {
        val series = (0 until 5).map { index ->
            SignalDecimation.Bucket(index * 300L, (index + 1) * 300L, index * 30.0, index * 30.0, index * 30.0, 1)
        }
        assertEquals(90.0, ouraHistoryTrend(series, 1).last(), 0.0)
        assertEquals(60.0, ouraHistoryTrend(series, 2).last(), 0.0)
        assertEquals(emptyList<Double>(), ouraHistoryTrend(emptyList(), 4))
    }
}
