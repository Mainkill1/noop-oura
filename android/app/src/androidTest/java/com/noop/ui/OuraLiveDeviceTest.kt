package com.noop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.noop.ble.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** All fixtures are invented receipts; this renders the production panel without VM, DB or BLE. */
class OuraLiveDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun explicitStartStopAndAxisVisibilityDoNotCreateSubscriptions() {
        var snapshot by mutableStateOf(OuraLiveSessionSnapshot(sourceDeviceId = "synthetic", generation = 999999))
        var starts = 0
        var stops = 0
        var selections = 0
        compose.setContent { Frame {
            OuraLivePanel(snapshot, 60000, OuraCategory.Movement, setOf("x", "y", "z"), true, true,
                OuraHistorySyncPhase.IDLE,
                onStart = { starts++; snapshot = populated().copy(selection = OuraLiveSelection(motion = true)) },
                onStop = { stops++; snapshot = snapshot.copy(active = false) },
                onComparison = { selections++ }, onHistory = {})
        } }
        compose.runOnIdle { assertEquals(0, starts) }
        compose.onNodeWithText("Start live").performScrollTo().performClick()
        compose.onNodeWithText("Stop live").assertExists()
        compose.onNodeWithTag("oura-live-trace-x").performScrollTo().performClick()
        compose.onNodeWithTag("oura-live-trace-y").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, starts); assertEquals(0, selections) }
        compose.onNodeWithText("Stop live").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
        compose.onNodeWithText("Live stopped", substring = true).assertExists()
        compose.onNodeWithTag("oura-live-plot").performScrollTo().assertIsDisplayed()
        capture("live-stopped-motion")
    }

    @Test fun comparisonInspectShowsRawMixedUnitsAndBothVectorsAtOneReceipt() {
        compose.setContent { Frame {
            OuraLivePanel(populated(), 60000, OuraCategory.Compare, setOf("hr", "x", "y", "z"), true, true,
                OuraHistorySyncPhase.PAUSED_FOR_LIVE, {}, {}, {}, {})
        } }
        val plot = compose.onNodeWithTag("oura-live-plot").performScrollTo()
        plot.performTouchInput { click(percentOffset(29f / 30f, .5f)) }
        compose.onNodeWithText("Heart rate: 81.0 bpm").assertExists()
        compose.onNodeWithText("X axis: 3000.0 raw counts / -1200.0 raw counts").assertExists()
        capture("live-compare-synthetic")
    }

    @Test fun perStreamFreshnessAndEmptyWaitingRemainDistinct() {
        val snapshot = OuraLiveSessionSnapshot(active = true, sourceDeviceId = "synthetic", generation = 888888,
            selection = OuraLiveSelection(true, true), startedAtElapsedMs = 50000,
            hr = OuraLiveStream(listOf(OuraLiveHrSample(59000, 81, null)), OuraLiveStreamPhase.RECEIVING, 59000),
            motion = OuraLiveStream(phase = OuraLiveStreamPhase.WAITING))
        compose.setContent { Frame {
            OuraLivePanel(snapshot, 60000, OuraCategory.Compare, setOf("hr", "x"), true, true,
                OuraHistorySyncPhase.SYNCING, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Heart rate · Receiving samples").performScrollTo().assertExists()
        compose.onNodeWithText("Updated 1 second ago").assertExists()
        compose.onNodeWithText("Movement · Waiting for first samples").performScrollTo().assertExists()
        capture("live-waiting-synthetic")
    }

    @Test fun largeFontKeepsControlsAndFourTracesReachable() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) { Frame {
                OuraLivePanel(populated(), 60000, OuraCategory.Compare, setOf("hr", "x", "y", "z"), true, true,
                    OuraHistorySyncPhase.PAUSED_FOR_LIVE, {}, {}, {}, {})
            } }
        }
        compose.onNodeWithText("Stop live").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("oura-live-trace-z").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("60 s").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("oura-live-plot").performScrollTo().assertIsDisplayed()
        capture("live-large-font-synthetic")
    }

    @Test fun recreationRetainsOnlyPresentationChoicesAndNeverStartsSession() {
        val restoration = StateRestorationTester(compose)
        var starts = 0
        restoration.setContent { Frame {
            OuraLivePanel(OuraLiveSessionSnapshot(sourceDeviceId = "synthetic", generation = 44444), 60000,
                OuraCategory.Health, setOf("hr"), true, true, OuraHistorySyncPhase.IDLE,
                { starts++ }, {}, {}, {})
        } }
        compose.onNodeWithText("10 s").performScrollTo().performClick()
        compose.onNodeWithText("Smoothed").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("10 s").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Smoothed").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Start live").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, starts) }
        capture("live-empty-synthetic")
    }

    @Test fun productionLifecycleCancelsPendingStartAndNeverResumesIt() {
        val intents = mutableListOf<OuraLiveIntent>()
        val owner = OuraLiveOwner(intents::add)
        val lifecycleOwner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        var present by mutableStateOf(true)
        compose.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent { if (present) OuraLiveLifecycle(owner, lifecycleOwner.lifecycle, "synthetic", true) }
        compose.runOnIdle {
            owner.start(OuraLiveSessionSnapshot(sourceDeviceId = "synthetic", generation = 919191), OuraLiveSelection(hr = true))
            lifecycleOwner.registry.currentState = Lifecycle.State.CREATED
            assertEquals(listOf(OuraLiveAction.Start, OuraLiveAction.Stop), intents.map { it.action })
            assertEquals(919191L, intents.last().generation)
            lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
            assertEquals(2, intents.size)
            owner.start(OuraLiveSessionSnapshot(sourceDeviceId = "synthetic", generation = 828282), OuraLiveSelection(motion = true))
            present = false
        }
        compose.runOnIdle {
            assertEquals(listOf(OuraLiveAction.Start, OuraLiveAction.Stop, OuraLiveAction.Start, OuraLiveAction.Stop), intents.map { it.action })
            assertEquals(828282L, intents.last().generation)
        }
    }

    private fun populated(): OuraLiveSessionSnapshot {
        val hr = (31..59).filter { it !in 43..49 }.map { i -> OuraLiveHrSample(i * 1000L, 70 + (i % 12), null) }
        val motion = (310..590).filter { it !in 430..490 }.flatMap { i ->
            listOf(OuraLiveMotionSample(i * 100L, i % 256, 0,
                if (i == 590) 3000 else (kotlin.math.sin(i / 9.0) * 900).toInt(),
                (kotlin.math.cos(i / 11.0) * 600).toInt(), 300, 0),
                OuraLiveMotionSample(i * 100L, i % 256, 1, if (i == 590) -1200 else 150, -300, 600, 0))
        }
        return OuraLiveSessionSnapshot(true, 123456789, OuraLiveSelection(true, true), 30000, "synthetic", 90000,
            OuraLiveStream(hr, OuraLiveStreamPhase.RECEIVING, 59000, 1.0),
            OuraLiveStream(motion, OuraLiveStreamPhase.RECEIVING, 59000, 20.0))
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
