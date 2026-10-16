package com.noop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.noop.analytics.SignalDecimation
import com.noop.ble.*
import com.noop.oura.*
import org.junit.Rule
import org.junit.Test

/** Invented readings exercise production chart gestures without a ring, database or worker. */
class OuraChartInteractionDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun historyInspectionPinchPanAndResetStayInline() {
        val summary = SignalDecimation.reduce(listOf(SignalDecimation.Sample(10, 60.0),
            SignalDecimation.Sample(650, 90.0)), 0, 900, 300)
        compose.setContent { Frame { OuraSignalCard("Synthetic HR", "bpm", "", summary, 0, 900, true) } }
        exercise("oura-history-plot")
    }

    @Test fun liveInspectionPinchAndResetKeepReceiptTimeAndMixedScales() {
        val snapshot = OuraLiveSessionSnapshot(active = true, sourceDeviceId = "synthetic", startedAtElapsedMs = 30000,
            selection = OuraLiveSelection(true, true),
            hr = OuraLiveStream(listOf(OuraLiveHrSample(50000, 64, null), OuraLiveHrSample(59000, 81, null))),
            motion = OuraLiveStream(listOf(OuraLiveMotionSample(59000, 1, 0, 123, -456, 789, 0))))
        compose.setContent { Frame { OuraLivePanel(snapshot, 60000, OuraCategory.Compare, setOf("hr", "x"),
            true, true, OuraHistorySyncPhase.PAUSED_FOR_LIVE, {}, {}) } }
        exercise("oura-live-plot")
        compose.onNodeWithText("Phone receipt time").assertExists()
    }

    @Test fun comparisonPinchDoesNotSelectMoreSignalsOrOpenDetails() {
        val summary = SignalDecimation.reduce(listOf(SignalDecimation.Sample(10, 60.0),
            SignalDecimation.Sample(650, 90.0)), 0, 900, 300)
        compose.setContent { Frame { OuraComparisonCard(listOf("hr", "temperature"),
            mapOf("hr" to summary, "temperature" to summary), 0, 900, false, TemperatureUnit.CELSIUS,
            listOf("hr", "temperature"), {}) } }
        exercise("oura-comparison-plot")
    }

    @Test fun rawGraphSupportsTheSameInlineGestures() {
        val buffer = OuraRawBuffer()
        for (time in listOf(50000L, 59000L)) OuraRawInstruments.capture(listOf(0x33, 14, 50, 7, 1, 0, 2, 0,
            3, 0, 0xff, 0xff, 0xfe, 0xff, 0xfd, 0xff)).forEach { buffer.append(it, time) }
        val state = OuraRawState("synthetic", 77, OuraRawPollSnapshot(generation = 990), buffer.snapshot())
        compose.setContent { Frame { OuraRawPanel(state, 60000, "motion", 2000, true, true, {}, {}) } }
        exercise("oura-raw-plot")
    }

    @Test fun heldLiveViewKeepsTheSameReadingsUntilTheBufferActuallyEvictsThem() {
        var now by mutableStateOf(60000L)
        val snapshot = OuraLiveSessionSnapshot(active = true, sourceDeviceId = "synthetic", startedAtElapsedMs = 10000,
            selection = OuraLiveSelection(hr = true),
            hr = OuraLiveStream(listOf(OuraLiveHrSample(40000, 64, null), OuraLiveHrSample(50000, 81, null))))
        compose.setContent { Frame { OuraLivePanel(snapshot, now, OuraCategory.Health, setOf("hr"),
            true, true, OuraHistorySyncPhase.PAUSED_FOR_LIVE, {}, {}) } }
        val plot = compose.onNodeWithTag("oura-live-plot").performScrollTo()
        plot.performTouchInput {
            pinch(start0 = percentOffset(.35f, .5f), end0 = percentOffset(.2f, .5f), start1 = percentOffset(.65f, .5f), end1 = percentOffset(.8f, .5f))
        }
        compose.onNodeWithTag("oura-live-plot-reset").assertExists()
        val before = axisLabels("oura-live-plot")
        assertEquals("Three Live time ticks must be observable", 3, before.size)
        compose.runOnIdle { now = 70000 }
        val after = axisLabels("oura-live-plot")
        // Holding absolute time makes each relative tick exactly ten seconds older.
        before.zip(after).forEach { (old, new) -> assertEquals(old.substringBefore(" s").toDouble() - 10.0, new.substringBefore(" s").toDouble(), .011) }
    }

    @Test fun rawGapInspectionDoesNotBorrowADistantPacket() {
        val buffer = OuraRawBuffer()
        for (time in listOf(50000L, 59000L)) OuraRawInstruments.capture(listOf(0x33, 14, 50, 7, 1, 0, 2, 0,
            3, 0, 0xff, 0xff, 0xfe, 0xff, 0xfd, 0xff)).forEach { buffer.append(it, time) }
        compose.setContent { Frame { OuraRawPanel(OuraRawState("synthetic", 77, OuraRawPollSnapshot(), buffer.snapshot()),
            60000, "motion", 2000, true, true, {}, {}) } }
        compose.onNodeWithTag("oura-raw-plot").performScrollTo().performTouchInput { click(center) }
        compose.onNodeWithText("x: —").assertExists()
        compose.onNodeWithText("y: —").assertExists()
        compose.onNodeWithText("z: —").assertExists()
    }

    @Test fun verticalGraphSwipeStillScrollsThePage() {
        val scroll = ScrollState(0)
        val summary = SignalDecimation.reduce(listOf(SignalDecimation.Sample(10, 60.0), SignalDecimation.Sample(650, 90.0)), 0, 900, 300)
        compose.setContent { NoopTheme { Surface(color = Palette.surfaceBase) {
            Column(Modifier.verticalScroll(scroll)) {
                Spacer(Modifier.height(Metrics.chartHeight))
                OuraSignalCard("Synthetic HR", "bpm", "", summary, 0, 900, true)
                Spacer(Modifier.height(Metrics.largeChartHeight * 2))
            }
        } } }
        val plot = compose.onNodeWithTag("oura-history-plot").performScrollTo()
        val before = scroll.value
        plot.performTouchInput { swipe(percentOffset(.5f, .8f), percentOffset(.5f, .2f)) }
        compose.runOnIdle { assertTrue("A vertical chart swipe must scroll its page", scroll.value > before) }
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun separateAxesShowTheActualTickValuesAndUnitsAtLargeText() {
        val hr = SignalDecimation.reduce(listOf(SignalDecimation.Sample(10, 60.0), SignalDecimation.Sample(650, 90.0)), 0, 900, 300)
        val temperature = SignalDecimation.reduce(listOf(SignalDecimation.Sample(10, 33.0), SignalDecimation.Sample(650, 35.0)), 0, 900, 300)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) { Frame {
            OuraComparisonCard(listOf("hr", "temperature"), mapOf("hr" to hr, "temperature" to temperature),
                0, 900, false, TemperatureUnit.CELSIUS, listOf("hr", "temperature"), {})
        } } }
        compose.onNodeWithTag("oura-comparison-plot").performScrollTo()
        for (tick in listOf("57.6", "75.0", "92.4", "32.5", "34.0", "35.5")) compose.onNodeWithText(tick).assertExists()
        capture("comparison-axes-large-font-synthetic")
    }

    private fun exercise(tag: String) {
        val plot = compose.onNodeWithTag(tag).performScrollTo()
        plot.performTouchInput { click(center) }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithTag("$tag-readout").assertExists()
        plot.performTouchInput { swipe(percentOffset(.1f, .5f), percentOffset(.8f, .5f)) }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithTag("$tag-y-axis").assertExists()
        compose.onNodeWithTag("$tag-x-axis").assertExists()
        val fullAxis = axisLabels(tag)
        assertEquals("Three time ticks must be observable", 3, fullAxis.size)
        plot.performTouchInput {
            pinch(start0 = percentOffset(.35f, .5f), end0 = percentOffset(.1f, .5f), start1 = percentOffset(.65f, .5f), end1 = percentOffset(.9f, .5f))
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        val zoomedAxis = axisLabels(tag)
        assertNotEquals("Pinch must change the visible times", fullAxis, zoomedAxis)
        plot.performTouchInput {
            down(0, percentOffset(.3f, .4f)); down(1, percentOffset(.6f, .6f))
            moveTo(0, percentOffset(.45f, .4f), delayMillis = 16)
            moveTo(1, percentOffset(.75f, .6f), delayMillis = 16)
            up(0); up(1)
        }
        assertNotEquals("Two-finger pan must move the time range", zoomedAxis, axisLabels(tag))
        capture("$tag-zoom-synthetic")
        compose.onNodeWithTag("$tag-reset").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("$tag-reset").assertDoesNotExist()
        assertEquals(fullAxis, axisLabels(tag))
    }

    private fun axisLabels(tag: String): List<String> = compose.onAllNodes(
        hasAnyAncestor(hasTestTag("$tag-x-axis")), useUnmergedTree = true).fetchSemanticsNodes()
        .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }.map { it.text }

    private fun capture(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.getExternalFilesDir(null), "oura-test-evidence")
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try { java.io.File(directory, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    @Composable private fun Frame(content: @Composable () -> Unit) {
        NoopTheme { Surface(color = Palette.surfaceBase) {
            Column(Modifier.verticalScroll(rememberScrollState())) { content() }
        } }
    }
}
