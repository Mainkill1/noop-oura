package com.noop.ui

import com.noop.analytics.SignalDecimation
import org.junit.Assert.*
import org.junit.Test

class OuraRecordedContextTest {
    @Test fun hostMeanGraphsUseStoredPeakEnvelopeWithoutChangingMeansOrRingDomains() {
        fun summary(value: Double) = SignalDecimation.reduce(listOf(SignalDecimation.Sample(1, value)), 0, 10, 10)
        val signals = mapOf("live_motion_x" to summary(10.0), "live_motion_x_min" to summary(-900.0),
            "live_motion_x_max" to summary(3000.0), "motion_x" to summary(128.0))
        val result = ouraRecordedDisplaySignals(signals)
        val host = result.getValue("live_motion_x")
        assertEquals(10.0, host.mean!!, 0.0)
        assertEquals(10.0, host.buckets.single().median, 0.0)
        assertEquals(-900.0, host.buckets.single().minimum, 0.0)
        assertEquals(3000.0, host.maximum!!, 0.0)
        assertEquals(128.0, result.getValue("motion_x").maximum!!, 0.0)
        assertEquals(10.0, signals.getValue("live_motion_x").maximum!!, 0.0)
        assertNotEquals(ouraComparisonDimension("motion_x"), ouraComparisonDimension("live_motion_x"))
    }
}
