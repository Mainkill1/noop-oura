package com.noop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.noop.analytics.SignalDecimation
import com.noop.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OuraExplorerDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sliderAndCategoryTabsHaveIndependentSelections() {
        var mode by mutableStateOf(OuraViewMode.History)
        var category by mutableStateOf(OuraCategory.Health)
        compose.setContent { Theme { OuraExplorerNavigation(mode, category, { mode = it }, { category = it }) } }
        compose.onNodeWithText("Live").performClick().assertIsSelected()
        compose.onNodeWithText("Movement").performClick().assertIsSelected()
        assertEquals(OuraViewMode.Live, mode)
        compose.onNodeWithText("History").performClick().assertIsSelected()
        assertEquals(OuraCategory.Movement, category)
        captureEvidence("explorer-navigation")
        compose.onNodeWithText("Sleep").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Live or history").performTouchInput { swipeLeft() }
        assertEquals(OuraViewMode.Live, mode)
        assertEquals(OuraCategory.Sleep, category)
    }

    @Test fun comparisonCapsSelectionsAtFourAndInspectionPreservesGaps() {
        val restoration = StateRestorationTester(compose)
        lateinit var selected: State<List<String>>
        val summary = SignalDecimation.reduce(listOf(SignalDecimation.Sample(10, 60.0),
            SignalDecimation.Sample(650, 90.0)), 0, 900, 300)
        val keys = listOf("hr", "temperature", "movement", "oxygen", "battery")
        restoration.setContent { Theme {
            val choice = rememberSaveable { mutableStateOf(emptyList<String>()) }
            selected = choice
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OuraComparisonCard(keys, keys.associateWith { summary }, 0, 900, false,
                    TemperatureUnit.CELSIUS, choice.value, { choice.value = it })
            }
        } }
        keys.take(4).forEach { compose.onNodeWithTag("oura-trace-$it").performScrollTo().performClick() }
        compose.onNodeWithTag("oura-trace-battery").assertIsNotEnabled()
        assertEquals(4, selected.value.size)
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(keys.take(4), selected.value)
        compose.onNodeWithTag("oura-trace-battery").assertIsNotEnabled()
        val plot = compose.onNodeWithTag("oura-comparison-plot").performScrollTo()
        plot.performTouchInput { click(percentOffset(0.15f, 0.5f)) }
        compose.onNodeWithText("60.0 bpm", substring = true).assertExists()
        captureEvidence("explorer-comparison-inspection")
        plot.performTouchInput { click(percentOffset(0.5f, 0.5f)) }
        assertTrue(compose.onAllNodesWithText("—").fetchSemanticsNodes().size >= 4)
        val next = plot.fetchSemanticsNode().config[SemanticsActions.CustomActions].last()
        compose.runOnIdle { assertTrue(next.action()) }
        compose.onNodeWithText("90.0 bpm", substring = true).assertExists()
    }

    @Test fun populatedComparisonAndMovementVisualReview() {
        val from = java.time.LocalDate.of(2026, 10, 13).atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond()
        val to = from + 86400
        fun signal(base: Double, amplitude: Double, phase: Double) = SignalDecimation.reduce((0 until 288)
            .filter { it !in 130..155 }.map { i -> SignalDecimation.Sample(from + i * 300L,
                base + amplitude * kotlin.math.sin(i / 18.0 + phase)) }, from, to, 300)
        val signals = mapOf("hr" to signal(65.0, 12.0, 0.0), "temperature" to signal(33.5, 0.7, 0.8),
            "motion_x" to signal(0.0, 128.0, 1.7), "motion_y" to signal(0.0, 64.0, 0.4))
        var category by mutableStateOf(OuraCategory.Compare)
        compose.setContent { Theme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OuraExplorerNavigation(OuraViewMode.History, category, {}, { category = it })
                if (category == OuraCategory.Compare) OuraComparisonCard(signals.keys.toList(), signals,
                    from, to, false, TemperatureUnit.CELSIUS, signals.keys.toList(), {})
                else OuraMovementDetails(listOf(OuraMotionWindow(from + 3600, 12.0, -64.0, 128.0, 8.0, 2.0, 9.0, 5)))
            }
        } }
        compose.onNodeWithTag("oura-comparison-plot").performScrollTo()
        captureEvidence("explorer-comparison-populated")
        compose.onNodeWithText("Movement").performScrollTo().performClick()
        compose.onNodeWithText("Orientation code").performScrollTo().assertIsDisplayed()
        captureEvidence("explorer-movement-details")
    }

    @Test fun motionWindowsKeepRawAxesOptionalFieldsAndSourceIsolation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, WhoopDatabase::class.java).build()
        try {
            db.whoopDao().insertEvents(listOf(
                EventRow("ring", 10, OuraStreamMapping.EVENT_MOTION,
                    """{"motion_seconds":12,"x":-64,"y":128,"z":8,"orientation":5,"low_intensity":2,"high_intensity":9}"""),
                EventRow("ring", 20, OuraStreamMapping.EVENT_MOTION, """{"motion_seconds":0,"x":0}"""),
                EventRow("ring", 30, OuraStreamMapping.EVENT_MOTION, "invalid"),
                EventRow("other", 10, OuraStreamMapping.EVENT_MOTION, """{"motion_seconds":31,"x":999}""")))
            val result = OuraDashboardData.load(WhoopRepository(db.whoopDao()), OuraDashboardSource("ring", "Ring"), 0, 100, 10)
            assertEquals(2, result.motionWindows.size)
            assertEquals(2, result.signals.getValue("motion_x").count)
            assertEquals(-64.0, result.signals.getValue("motion_x").minimum!!, 0.0)
            assertEquals(1, result.signals.getValue("motion_y").count)
            assertNull(result.motionWindows.first().y)
            assertEquals(5, result.motionWindows.last().orientation)
            assertFalse(result.signals.containsKey("orientation"))
        } finally { db.close() }
    }

    @Test fun invalidMotionFieldsCannotDistortScales() {
        val parsed = OuraMotionWindow.parse(1, """{"motion_seconds":12,"x":1e308,"y":1,"z":-1024,"low_intensity":1.5,"high_intensity":63,"orientation":8}""")!!
        assertNull(parsed.x)
        assertNull(parsed.y)
        assertEquals(-1024.0, parsed.z!!, 0.0)
        assertNull(parsed.low)
        assertEquals(63.0, parsed.high!!, 0.0)
        assertNull(parsed.orientation)
        assertEquals(1016.0, OuraMotionWindow.parse(1, """{"motion_seconds":31,"x":1016}""")!!.x!!, 0.0)
        assertNull(OuraMotionWindow.parse(1, """{"motion_seconds":1.5}"""))
    }

    private fun captureEvidence(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.getExternalFilesDir(null), "oura-test-evidence")
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            java.io.File(directory, "$name.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    @Composable private fun Theme(content: @Composable () -> Unit) {
        NoopTheme { Surface(color = Palette.surfaceBase, content = content) }
    }
}
