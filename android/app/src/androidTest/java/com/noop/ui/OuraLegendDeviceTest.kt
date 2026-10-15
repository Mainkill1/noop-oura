package com.noop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.noop.analytics.SignalDecimation
import com.noop.ble.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Invented values in production chart surfaces; no user database or BLE writes. */
class OuraLegendDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun liveLegendNamesAllFourTracesAndRetainsStylesWhenAnAxisIsHidden() {
        var selected by mutableStateOf(setOf("hr", "x", "y", "z"))
        compose.setContent { Frame(largeText = true) {
            OuraLivePanel(OuraLiveSessionSnapshot(active = true, sourceDeviceId = "synthetic",
                selection = OuraLiveSelection(true, true), startedAtElapsedMs = 10000,
                hr = OuraLiveStream(listOf(OuraLiveHrSample(11000, 72, null)), OuraLiveStreamPhase.RECEIVING, 11000),
                motion = OuraLiveStream(listOf(OuraLiveMotionSample(11000, 1, 0, -100, 200, 300, 0)), OuraLiveStreamPhase.RECEIVING, 11000)),
                12000, OuraCategory.Compare, selected, true, true, OuraHistorySyncPhase.PAUSED_FOR_LIVE,
                { selected = it }, {})
        } }
        compose.onNodeWithTag("oura-legend-hr").assertTextContains("bpm", substring = true)
        for (axis in listOf("x", "y", "z"))
            compose.onNodeWithTag("oura-legend-$axis").assertTextContains("raw counts", substring = true)
        compose.onNodeWithTag("oura-legend-y").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Dashed line"))
        compose.onNodeWithTag("oura-legend-z").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Dotted line"))
        compose.onNodeWithTag("oura-chart-legend").performScrollTo().assertIsDisplayed()
        val legend = compose.onNodeWithTag("oura-chart-legend").getUnclippedBoundsInRoot()
        val plot = compose.onNodeWithTag("oura-live-plot").getUnclippedBoundsInRoot()
        assertTrue(legend.bottom <= plot.top)
        capture("legend-live-large-font-synthetic")
        compose.runOnIdle { selected = selected - "x" }
        compose.onNodeWithTag("oura-legend-x").assertDoesNotExist()
        compose.onNodeWithTag("oura-legend-y").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Dashed line"))
    }

    @Test fun historyLegendDistinguishesMedianFromOptionalRange() {
        var ranges by mutableStateOf(true)
        compose.setContent { Frame {
            OuraSignalCard("Synthetic heart rate", "bpm", "", summary(), 990, 1300, ranges)
        } }
        compose.onNodeWithTag("oura-legend-median").assertTextContains("Median", substring = true)
        compose.onNodeWithTag("oura-legend-range").assertTextContains("Min–max range", substring = true)
        compose.onNodeWithTag("oura-chart-legend").performScrollTo().assertIsDisplayed()
        capture("legend-history-range-synthetic")
        compose.runOnIdle { ranges = false }
        compose.onNodeWithTag("oura-legend-range").assertDoesNotExist()
        compose.onNodeWithTag("oura-legend-median").assertExists()
    }

    @Test fun comparisonLegendIncludesEachSignalAndItsOwnUnit() {
        compose.setContent { Frame {
            OuraComparisonCard(listOf("hr", "temperature"), mapOf("hr" to summary(), "temperature" to summary()),
                990, 1300, false, TemperatureUnit.CELSIUS, listOf("hr", "temperature"), {})
        } }
        compose.onNodeWithTag("oura-legend-hr").assertTextContains("bpm", substring = true)
        compose.onNodeWithTag("oura-legend-temperature").assertTextContains("°C", substring = true)
        compose.onNodeWithTag("oura-chart-legend").performScrollTo().assertIsDisplayed()
        capture("legend-history-compare-synthetic")
    }

    @Test fun oxygenConfigurationNeverPretendsToBeALiveMeasurement() {
        var mode by mutableStateOf<Int?>(0)
        var history = 0
        compose.setContent { Frame {
            OuraLivePanel(OuraLiveSessionSnapshot(sourceDeviceId = "synthetic"), 12000,
                OuraCategory.Health, setOf("hr"), true, true, OuraHistorySyncPhase.IDLE, {}, { history++ }, oxygenMode = mode)
        } }
        compose.onNodeWithText("The ring reports oxygen sensing is off.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("O₂ · recorded only").assertExists()
        compose.onNodeWithText("Updated", substring = true).assertDoesNotExist()
        capture("oxygen-off-live-status-synthetic")
        compose.runOnIdle { mode = 1 }
        compose.onNodeWithText("The ring reports oxygen sensing is enabled.").assertExists()
        compose.onNodeWithText("Updated", substring = true).assertDoesNotExist()
        compose.runOnIdle { mode = null }
        compose.onNodeWithText("Oxygen sensing status is unavailable.").assertExists()
        compose.onNodeWithText("View oxygen history").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, history) }
    }

    private fun summary() = SignalDecimation.reduce(listOf(
        SignalDecimation.Sample(1000, 65.0), SignalDecimation.Sample(1050, 75.0),
        SignalDecimation.Sample(1100, 70.0)), 990, 1300, 60)

    @Composable private fun Frame(largeText: Boolean = false, content: @Composable () -> Unit) {
        NoopTheme { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,
            if (largeText) 2f else LocalDensity.current.fontScale)) {
            Surface(color = Palette.surfaceBase) { Column(Modifier.verticalScroll(rememberScrollState())) { content() } }
        } }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.getExternalFilesDir(null), "oura-test-evidence")
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try { java.io.File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        } } finally { bitmap.recycle() }
    }
}
