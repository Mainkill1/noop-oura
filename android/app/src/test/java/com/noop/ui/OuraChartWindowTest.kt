package com.noop.ui

import org.junit.Assert.*
import org.junit.Test
import androidx.compose.runtime.mutableStateOf

class OuraChartWindowTest {
    @Test fun pinchKeepsTheMeasurementUnderTheFingersAtTheSamePosition() {
        assertEquals(OuraChartWindow(250, 750), ouraChartTransform(OuraChartWindow(0, 1000), OuraChartWindow(0, 1000), 2f, .5f, 0f))
        assertEquals(OuraChartWindow(600, 800), ouraChartTransform(OuraChartWindow(200, 1000), OuraChartWindow(0, 1000), 4f, 2f / 3f, 0f))
    }

    @Test fun panningAndZoomingOutCannotEscapeTheAvailableTimeRange() {
        val domain = OuraChartWindow(0, 1000)
        assertEquals(OuraChartWindow(0, 500), ouraChartTransform(OuraChartWindow(250, 750), domain, 1f, .5f, 1f))
        assertEquals(OuraChartWindow(500, 1000), ouraChartTransform(OuraChartWindow(250, 750), domain, 1f, .5f, -1f))
        assertEquals(domain, ouraChartTransform(OuraChartWindow(250, 750), domain, .1f, .5f, 0f))
    }

    @Test fun zoomLimitPreventsZeroWidthAndInvalidGesturesLeaveTheWindowAlone() {
        val domain = OuraChartWindow(0, 6400)
        assertEquals(OuraChartWindow(3150, 3250), ouraChartTransform(domain, domain, 10000f, .5f, 0f))
        assertEquals(domain, ouraChartTransform(domain, domain, Float.NaN, .5f, 0f))
        assertEquals(domain, ouraChartTransform(domain, domain, 0f, .5f, 0f))
        assertEquals(OuraChartWindow(0, 1), ouraChartTransform(OuraChartWindow(0, 1), OuraChartWindow(0, 1), 4f, .5f, 0f))
    }

    @Test fun heldLiveWindowStaysFixedAsNewReceiptsArriveAndClampsAfterEviction() {
        val held = OuraChartWindow(400, 600)
        assertEquals(held, ouraChartClamp(held, OuraChartWindow(100, 1100)))
        assertEquals(OuraChartWindow(500, 700), ouraChartClamp(held, OuraChartWindow(500, 1500)))
    }

    @Test fun nonDivisibleTimeRangeNeverZoomsPastSixtyFourTimes() {
        val domain = OuraChartWindow(0, 901)
        assertEquals(OuraChartWindow(443, 458), ouraChartTransform(domain, domain, 10000f, .5f, 0f))
    }

    @Test fun liveZoomLimitUsesTheSelectedWindowRatherThanAllRetainedData() {
        val domain = OuraChartWindow(0, 60000)
        val result = ouraChartTransform(OuraChartWindow(30000, 60000), domain, 10000f, .5f, 0f, 30000)
        assertEquals(469L, result.span)
    }

    @Test fun liveViewportHoldsAbsoluteTimeWhileResetResumesTheSelectedFollowRange() {
        val available = mutableStateOf(OuraChartWindow(0, 60000))
        val follow = mutableStateOf(OuraChartWindow(30000, 60000))
        val viewport = OuraChartViewport(available, follow)
        viewport.transform(2f, .5f, 0f)
        available.value = OuraChartWindow(10000, 70000)
        follow.value = OuraChartWindow(40000, 70000)
        assertEquals(OuraChartWindow(37500, 52500), viewport.window)
        viewport.reset()
        assertEquals(OuraChartWindow(40000, 70000), viewport.window)
    }

    @Test fun followViewKeepsLeadingBlankTimeAfterCountEvictionWhileZoomClampsToRetainedData() {
        val viewport = OuraChartViewport(mutableStateOf(OuraChartWindow(40000, 60000)),
            mutableStateOf(OuraChartWindow(30000, 60000)))
        assertEquals(OuraChartWindow(30000, 60000), viewport.window)
        viewport.transform(2f, .5f, 0f)
        assertEquals(OuraChartWindow(40000, 55000), viewport.window)
        viewport.reset()
        assertEquals(OuraChartWindow(30000, 60000), viewport.window)
    }
}
