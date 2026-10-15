package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.noop.analytics.SignalDecimation
import com.noop.data.*
import com.noop.oura.OuraLiveMotionSummary
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic production surfaces only: no paired source, user database, or BLE. */
class OuraIndustrialDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun categoryDropdownLargeFontAndModeSwipeRemainIndependent() {
        var mode by mutableStateOf(OuraViewMode.History)
        var category by mutableStateOf(OuraCategory.Health)
        compose.setContent { NoopTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                OuraExplorerNavigation(mode, category, { mode = it }, { category = it })
            }
        } }
        OuraCategory.entries.forEach { next ->
            compose.onNodeWithTag("oura-category-picker").assertIsDisplayed().performClick()
            compose.onNodeWithTag("oura-category-${next.name}").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(next, category); assertEquals(OuraViewMode.History, mode) }
        }
        // Swipe over the visible Live segment: its position must agree with the drag hit region.
        compose.onNodeWithText("Live").performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(OuraViewMode.Live, mode); assertEquals(OuraCategory.Compare, category) }
        compose.onNodeWithText("History").performClick().assertIsSelected()
        capture("industrial-category-large-font-synthetic")
    }

    @Test fun compactProductionNavigationSourceAndRecordedHealthAreVisible() {
        compose.setContent { Frame {
            OuraExplorerNavigation(OuraViewMode.Live, OuraCategory.Health, {}, {})
            OuraControls(listOf(OuraDashboardSource("synthetic", "Synthetic ring")), "synthetic",
                java.time.LocalDate.of(2026, 10, 13), 1, false, false,
                {}, {}, {}, {}, {}, {}, {}, isLive = true)
            OuraRecordedHealth(OuraDashboardData(), TemperatureUnit.CELSIUS, {})
        } }
        compose.onNodeWithTag("oura-category-picker").assertIsDisplayed()
        compose.onNodeWithTag("oura-source-picker").assertIsDisplayed()
        capture("industrial-health-navigation-normal-synthetic")
    }

    @Test fun recordedOxygenRemainsVisibleWhenAbsentAndPresentWithoutLiveFreshness() {
        var present by mutableStateOf(false)
        val oxygen = SignalDecimation.reduce(listOf(SignalDecimation.Sample(1000, 94.0)), 0, 2000, 300)
        compose.setContent { Frame {
            OuraRecordedHealth(OuraDashboardData(signals = if (present) mapOf("oxygen" to oxygen) else emptyMap()),
                TemperatureUnit.CELSIUS, {})
        } }
        compose.onNodeWithText("Oxygen signal · unvalidated").performScrollTo().assertIsDisplayed()
        capture("industrial-recorded-oxygen-empty-synthetic")
        compose.runOnIdle { present = true }
        compose.onNodeWithText("94.0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Updated", substring = true).assertDoesNotExist()
        capture("industrial-recorded-oxygen-present-synthetic")
    }

    @Test fun storedHostMotionShowsCountsMeansPeaksAndMeasuredOverlayClearance() {
        val originalHeight = BottomBarStyleStore.barHeight
        lateinit var listState: LazyListState
        val record = OuraLiveMotionHistory(1000, OuraLiveMotionSummary(mapOf(
            "count" to 2L, "sum_x" to 1800L, "sum_y" to -20L, "sum_z" to 600L,
            "min_x" to -1200L, "max_x" to 3000L, "min_y" to -30L, "max_y" to 10L,
            "min_z" to 200L, "max_z" to 400L, "first_receipt_ms" to 1000100L, "last_receipt_ms" to 1000900L)))
        fun summary(values: List<Double>) = SignalDecimation.reduce(values.mapIndexed { index, value ->
            SignalDecimation.Sample(1000 + index.toLong(), value) }, 990, 1010, 1)
        val graph = ouraRecordedDisplaySignals(mapOf("live_motion_x" to summary(listOf(900.0, -300.0, 600.0, 0.0)),
            "live_motion_x_min" to summary(listOf(-1200.0, -900.0, -300.0, -600.0)),
            "live_motion_x_max" to summary(listOf(3000.0, 900.0, 1500.0, 600.0)))).getValue("live_motion_x")
        try {
            compose.runOnIdle { BottomBarStyleStore.barHeight = 128.dp }
            compose.setContent { NoopTheme {
                listState = rememberLazyListState()
                Box(Modifier.fillMaxSize()) {
                    LazyScreenScaffold(title = null, listState = listState) {
                        item { OuraExplorerNavigation(OuraViewMode.History, OuraCategory.Movement, {}, {}) }
                        item { OuraSignalCard("Host X mean", "raw counts", "Synthetic host capture", graph, 990, 1010, true,
                            modifier = Modifier.testTag("synthetic-host-graph")) }
                        items(8) { OuraPanel { androidx.compose.material3.Text("Synthetic context", style = NoopType.body) } }
                        item { OuraLiveMotionDetails(listOf(record)) }
                    }
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(BottomBarStyleStore.barHeight)
                        .background(Palette.surfaceOverlay).testTag("synthetic-overlay"))
                }
            } }
            compose.onNodeWithTag("synthetic-host-graph").performScrollTo()
            capture("industrial-host-history-graph-synthetic")
            compose.onNodeWithText("2 vectors").performScrollTo().assertExists()
            compose.onNodeWithText("X axis: mean 900.0 · min -1200 · max 3000").assertExists()
            compose.onNodeWithTag("oura-host-last-detail").performScrollTo()
            compose.runOnIdle { kotlinx.coroutines.runBlocking { listState.scrollToItem(10) } }
            val detail = compose.onNodeWithTag("oura-host-last-detail").fetchSemanticsNode().boundsInRoot
            val overlay = compose.onNodeWithTag("synthetic-overlay").fetchSemanticsNode().boundsInRoot
            assertTrue("Last detail must scroll above the measured overlay", detail.bottom <= overlay.top)
            capture("industrial-host-history-bottom-clearance-synthetic")
        } finally { compose.runOnIdle { BottomBarStyleStore.barHeight = originalHeight } }
    }

    @Composable private fun Frame(content: @Composable () -> Unit) {
        NoopTheme { Surface(color = Palette.surfaceBase) { Column(Modifier.verticalScroll(rememberScrollState())) { content() } } }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.getExternalFilesDir(null), "oura-test-evidence")
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try { java.io.File(directory, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
}
