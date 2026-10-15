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

    @Test fun automaticPageEntryAndAxisVisibilityUseOnlyOneMotionSubscription() {
        var snapshot by mutableStateOf(OuraLiveSessionSnapshot(sourceDeviceId = "synthetic", generation = 999999))
        val intents = mutableListOf<OuraLiveIntent>()
        val owner = OuraLiveOwner { intent ->
            intents.add(intent)
            if (intent.action == OuraLiveAction.Start) snapshot = populated().copy(selection = intent.selection)
        }
        val lifecycleOwner = testLifecycleOwner()
        compose.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent { Frame {
            OuraLiveLifecycle(owner, lifecycleOwner.lifecycle, "synthetic", true, snapshot, true, OuraLiveSelection(motion = true))
            OuraLivePanel(snapshot, 60000, OuraCategory.Movement, setOf("x", "y", "z"), true, true,
                OuraHistorySyncPhase.IDLE, {}, {})
        } }
        compose.onNodeWithText("Start live").assertDoesNotExist()
        compose.onNodeWithTag("oura-live-trace-x").performScrollTo().performClick()
        compose.onNodeWithTag("oura-live-trace-y").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(OuraLiveAction.Start), intents.map { it.action }) }
        compose.onNodeWithTag("oura-live-trace-x").performScrollTo().performClick()
        compose.onNodeWithTag("oura-live-trace-y").performScrollTo().performClick()
        compose.onNodeWithTag("oura-live-plot").performScrollTo().assertIsDisplayed()
        capture("live-motion-raw-synthetic")
        compose.onNodeWithText("Smoothed").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("oura-live-plot").performScrollTo()
        capture("live-motion-smoothed-synthetic")
        compose.runOnIdle {
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            assertEquals(listOf(OuraLiveAction.Start, OuraLiveAction.Stop), intents.map { it.action })
            assertEquals(999999L, intents.last().generation)
        }
    }

    @Test fun comparisonInspectShowsRawMixedUnitsAndBothVectorsAtOneReceipt() {
        compose.setContent { Frame {
            OuraLivePanel(populated(), 60000, OuraCategory.Compare, setOf("hr", "x", "y", "z"), true, true,
                OuraHistorySyncPhase.PAUSED_FOR_LIVE, {}, {})
        } }
        val plot = compose.onNodeWithTag("oura-live-plot").performScrollTo()
        plot.performTouchInput { click(percentOffset(29f / 30f, .5f)) }
        compose.onNodeWithText("Heart rate: 81.0 bpm").assertExists()
        compose.onNodeWithText("X axis: 3000.0 raw counts / -1200.0 raw counts").assertExists()
        capture("live-compare-synthetic")
    }

    @Test fun gapCursorAndUnequalReceiptTimesNeverInventAnUpdate() {
        val snapshot = OuraLiveSessionSnapshot(active = true, sourceDeviceId = "synthetic", generation = 121212,
            selection = OuraLiveSelection(true, true), startedAtElapsedMs = 30000,
            hr = OuraLiveStream(listOf(OuraLiveHrSample(50000, 64, null), OuraLiveHrSample(59000, 81, null)),
                OuraLiveStreamPhase.RECEIVING, 59000),
            motion = OuraLiveStream(listOf(OuraLiveMotionSample(57600, 1, 0, 123, -456, 789, 0)),
                OuraLiveStreamPhase.STALE, 57600))
        compose.setContent { Frame {
            OuraLivePanel(snapshot, 60000, OuraCategory.Compare, setOf("hr", "x"), true, true,
                OuraHistorySyncPhase.PAUSED_FOR_LIVE, {}, {})
        } }
        val plot = compose.onNodeWithTag("oura-live-plot").performScrollTo()
        plot.performTouchInput { click(percentOffset(25f / 30f, .5f)) }
        compose.onNodeWithText("Heart rate: —").assertExists()
        compose.onNodeWithText("X axis: —").assertExists()
        compose.onNodeWithText("Updated 5 seconds ago").assertDoesNotExist()
        compose.onNodeWithText("Cursor: 5 seconds ago").assertExists()
        plot.performTouchInput { click(percentOffset(28f / 30f, .5f)) }
        compose.onNodeWithText("Heart rate: 81.0 bpm").assertExists()
        compose.onNodeWithText("X axis: 123.0 raw counts").assertExists()
        compose.onNodeWithText("Cursor: 2 seconds ago").assertExists()
        compose.onNodeWithText("Updated 1 second ago").assertExists()
        compose.onNodeWithText("Updated 2 seconds ago").assertExists()
        capture("live-gap-cursor-synthetic")
    }

    @Test fun perStreamFreshnessAndEmptyWaitingRemainDistinct() {
        val snapshot = OuraLiveSessionSnapshot(active = true, sourceDeviceId = "synthetic", generation = 888888,
            selection = OuraLiveSelection(true, true), startedAtElapsedMs = 50000,
            hr = OuraLiveStream(listOf(OuraLiveHrSample(59000, 81, null)), OuraLiveStreamPhase.RECEIVING, 59000),
            motion = OuraLiveStream(phase = OuraLiveStreamPhase.WAITING))
        compose.setContent { Frame {
            OuraLivePanel(snapshot, 60000, OuraCategory.Compare, setOf("hr", "x"), true, true,
                OuraHistorySyncPhase.SYNCING, {}, {})
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
                    OuraHistorySyncPhase.PAUSED_FOR_LIVE, {}, {})
            } }
        }
        compose.onNodeWithText("Stop live").assertDoesNotExist()
        compose.onNodeWithTag("oura-live-trace-z").performScrollTo().assertIsDisplayed()
        // A large-font pill scrolls horizontally; first bring its ROW through the vertical viewport.
        compose.onNodeWithTag("oura-live-window").performScrollTo().assertIsDisplayed()
        capture("live-large-font-controls-synthetic")
        compose.onNodeWithText("60 s").performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
        compose.onNodeWithTag("oura-live-plot").performScrollTo().assertIsDisplayed()
        capture("live-large-font-synthetic")
    }

    @Test fun recreationRetainsOnlyPresentationChoices() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Frame {
            OuraLivePanel(OuraLiveSessionSnapshot(sourceDeviceId = "synthetic", generation = 44444), 60000,
                OuraCategory.Health, setOf("hr"), true, true, OuraHistorySyncPhase.IDLE,
                {}, {})
        } }
        compose.onNodeWithText("10 s").performScrollTo().performClick()
        compose.onNodeWithText("Smoothed").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("10 s").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Smoothed").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Start live").assertDoesNotExist()
        capture("live-empty-synthetic")
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private fun testLifecycleOwner() = TestLifecycleOwner()

    @Test fun productionLifecycleStopsOnPauseBeforeStopAndResumesCurrentDemand() {
        val intents = mutableListOf<OuraLiveIntent>()
        val owner = OuraLiveOwner(intents::add)
        val life = testLifecycleOwner()
        var present by mutableStateOf(true)
        var selection by mutableStateOf(OuraLiveSelection(hr = true))
        var snapshot by mutableStateOf(OuraLiveSessionSnapshot(sourceDeviceId = "synthetic", generation = 919191))
        compose.runOnIdle { life.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent { if (present) OuraLiveLifecycle(owner, life.lifecycle, "synthetic", true,
            snapshot, true, selection) }
        compose.runOnIdle {
            assertEquals(listOf(OuraLiveAction.Start), intents.map { it.action })
            life.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            assertEquals(listOf(OuraLiveAction.Start, OuraLiveAction.Stop), intents.map { it.action })
            assertEquals(Lifecycle.State.STARTED, life.lifecycle.currentState)
            selection = OuraLiveSelection(motion = true)
            snapshot = snapshot.copy(generation = 919193)
        }
        compose.runOnIdle { life.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        compose.runOnIdle {
            assertEquals(OuraLiveSelection(motion = true), intents.last().selection)
            assertEquals(3, intents.size)
            present = false
        }
        compose.runOnIdle { assertEquals(listOf(OuraLiveAction.Start, OuraLiveAction.Stop, OuraLiveAction.Start, OuraLiveAction.Stop), intents.map { it.action }) }
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
