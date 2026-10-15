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
import com.noop.ble.*
import com.noop.oura.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

// Invented wire observations only. Production panel, selectors, legend and scaffold, without VM/DB/BLE.
class OuraRawDeviceTest {
    @get:Rule val compose = createComposeRule()
    private fun state(vararg bytes: Int): OuraRawState {
        val memory = OuraRawBuffer()
        OuraRawInstruments.capture(bytes.toList()).forEach { memory.append(it, 59000) }
        return OuraRawState("synthetic", 77, OuraRawPollSnapshot(generation = 990), memory.snapshot())
    }
    @Test fun selectorsRetainUnfilteredQualityAndNumbersWithUnits() {
        val data = state(0x80, 10, 1, 0, 0, 0, 0, 0, 100, 8, 255, 31)
        compose.setContent { Frame {
            OuraRawPanel(data, 60000, "event:80", 2000, true, true, {}, {})
        } }
        compose.onNodeWithTag("oura-raw-display").performScrollTo().performClick()
        compose.onNodeWithTag("oura-raw-display-Numbers").performScrollTo().performClick()
        compose.onNodeWithTag("oura-raw-statistics-ibi").performScrollTo()
            .assertTextEquals("Latest 2047.00 · min 0.00 · max 2047.00 · mean 949.00 · count 3")
        compose.onNodeWithText("ibi · ms").assertExists()
        compose.onNodeWithTag("oura-raw-field").performScrollTo().performClick()
        compose.onNodeWithTag("oura-raw-field-quality").performScrollTo().performClick()
        compose.onNodeWithTag("oura-raw-statistics-quality").performScrollTo()
            .assertTextEquals("Latest 3.00 · min 0.00 · max 3.00 · mean 1.33 · count 3")
        capture("raw-quality-numbers-synthetic")
    }
    @Test fun researchRawBytesAndEmptyStateNeverClaimOxygenOrAge() {
        var channel by mutableStateOf("event:8b")
        val data = state(0x8b, 8, 1, 0, 0, 0, 0x80, 255, 0, 65)
        compose.setContent { Frame {
            OuraRawPanel(data, 60000, channel, 2000, true, true, { channel = it }, {})
        } }
        compose.onNodeWithTag("oura-raw-display").performScrollTo().performClick()
        compose.onNodeWithTag("oura-raw-display-Bytes").performScrollTo().performClick()
        compose.onNodeWithTag("oura-raw-hex").performScrollTo().assertTextEquals("8B 08 01 00 00 00 80 FF 00 41")
        compose.onNodeWithText("Actual wire frame · 10 bytes").assertExists()
        capture("raw-research-bytes-synthetic")
        compose.onNodeWithTag("oura-raw-channel").performScrollTo().performClick()
        compose.onNodeWithTag("oura-raw-channel-event:81").performScrollTo().performClick()
        compose.onNodeWithTag("oura-raw-empty").performScrollTo().assertTextEquals("No record retained for this channel · awaiting normal history sync. This event may be unsupported by this ring.")
        compose.onNodeWithText("SpO2%", substring = true).assertDoesNotExist()
        capture("raw-ppg-empty-synthetic")
    }
    @Test fun xyzGraphAndObservedGaugeKeepActualSignedCountsAtLargeFont() {
        val data = state(0x33, 14, 50, 7, 1, 0, 2, 0, 3, 0, 0xff, 0xff, 0xfe, 0xff, 0xfd, 0xff)
        compose.setContent { Frame(large = true) {
            OuraRawPanel(data, 60000, "motion", 2000, true, true, {}, {})
        } }
        for (axis in listOf("x", "y", "z")) compose.onNodeWithTag("oura-legend-$axis").assertTextContains("raw counts", substring = true)
        compose.onNodeWithTag("oura-raw-plot").performScrollTo().assertIsDisplayed()
        capture("raw-xyz-graph-large-font-synthetic")
        compose.onNodeWithTag("oura-raw-display").performScrollTo().performClick()
        compose.onNodeWithTag("oura-raw-display-Gauge").performScrollTo().performClick()
        compose.onNodeWithTag("oura-raw-statistics-z").performScrollTo()
            .assertTextEquals("Latest -3.00 · min -3.00 · max 3.00 · mean 0.00 · count 2")
        capture("raw-xyz-gauge-synthetic")
    }
    @Test fun pollPresetsAndBottomDetailRemainAboveMeasuredNavigationOverlay() {
        val originalHeight = BottomBarStyleStore.barHeight
        lateinit var listState: LazyListState
        var interval by mutableStateOf(2000L)
        val data = state(0x0d, 3, 90, 0, 0)
        try {
            compose.runOnIdle { BottomBarStyleStore.barHeight = 128.dp }
            compose.setContent { NoopTheme {
                listState = rememberLazyListState()
                Box(Modifier.fillMaxSize()) {
                    LazyScreenScaffold(title = null, listState = listState) {
                        item { OuraExplorerNavigation(OuraViewMode.History, OuraCategory.Raw, {}, {}) }
                        item { OuraRawPanel(data, 60000, "battery", interval, true, true, {}, { interval = it }) }
                    }
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(BottomBarStyleStore.barHeight)
                        .background(Palette.surfaceOverlay).testTag("synthetic-raw-overlay"))
                }
            } }
            compose.runOnIdle { kotlinx.coroutines.runBlocking { listState.scrollToItem(1) } }
            compose.onNodeWithTag("oura-raw-interval").performScrollTo().performClick()
            compose.onNodeWithTag("oura-raw-interval-250").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(250L, interval) }
            compose.onNodeWithTag("oura-raw-last-detail").performScrollTo().assertIsDisplayed()
            val detail = compose.onNodeWithTag("oura-raw-last-detail").getUnclippedBoundsInRoot()
            val overlay = compose.onNodeWithTag("synthetic-raw-overlay").getUnclippedBoundsInRoot()
            assertTrue("Raw detail must clear measured bottom navigation", detail.bottom <= overlay.top)
            capture("raw-bottom-clearance-synthetic")
        } finally { compose.runOnIdle { BottomBarStyleStore.barHeight = originalHeight } }
    }
    @Composable private fun Frame(large: Boolean = false, content: @Composable () -> Unit) {
        NoopTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, if (large) 2f else 1f)) {
                Surface(color = Palette.surfaceBase) { Column(Modifier.verticalScroll(rememberScrollState())) { content() } }
            }
        }
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
