package com.noop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.noop.analytics.SignalDecimation
import com.noop.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/** Real Room reads and Compose interactions on a phone; all records are synthetic and in memory. */
class OuraDashboardDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun populatedDashboardVisualReview() {
        val day = LocalDate.of(2026, 10, 13)
        val from = day.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        val to = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        val summary = SignalDecimation.reduce((0 until 288).filter { it !in 130..160 }.map {
            SignalDecimation.Sample(from + it * 300L, 65.0 + 12 * kotlin.math.sin(it / 18.0) + 4 * kotlin.math.cos(it / 5.0))
        }, from, to, 300)
        compose.setContent {
            TestTheme {
                LazyScreenScaffold(title = "Oura", subtitle = "Your ring, your history, clearer signals.") {
                    item { OuraControls(listOf(OuraDashboardSource("fixture", "Oura Ring")), "fixture", day, 1,
                        false, false, {}, {}, {}, {}, {}, {}, {}) }
                    item { OuraSignalCard("Heart rate", "bpm", "", summary, from, to, false) }
                }
            }
        }
        captureEvidence("dashboard-populated")
        compose.onNodeWithText("Heart rate").performScrollTo()
        captureEvidence("dashboard-chart")
    }

    @Test fun ringReadsStayIsolatedAndKeepSingleChannelOxygen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, WhoopDatabase::class.java).build()
        try {
            val dao = db.whoopDao()
            dao.insertHr(listOf(HrSample("oura-test", 10, 60), HrSample("oura-test", 20, 80),
                HrSample("other-ring", 15, 200), HrSample("oura-test", 86400, 100)))
            dao.insertSpo2(listOf(Spo2Sample("oura-test", 10, 98, 0),
                Spo2Sample("oura-test", 20, 105, 0), Spo2Sample("oura-test", 30, 12000, 0),
                Spo2Sample("oura-test", 40, 98, 100)))
            dao.insertSkinTemp(listOf(SkinTempSample("oura-test", 10, 3250)))
            val data = OuraDashboardData.load(WhoopRepository(dao), OuraDashboardSource("oura-test", "Test ring"), 0, 86400, 300)
            assertEquals(2, data.signals.getValue("hr").count)
            assertEquals(70.0, data.signals.getValue("hr").mean!!, 0.0)
            assertEquals(2, data.signals.getValue("oxygen").count)
            assertEquals(101.5, data.signals.getValue("oxygen").mean!!, 0.0)
            assertEquals(32.5, data.signals.getValue("temperature").mean!!, 0.0)
            assertEquals(0, data.signals.getValue("battery").count)
        } finally { db.close() }
    }

    @Test fun importsUseCalendarDaysAndMetricSeriesPrecedenceAcrossDst() = runBlocking {
        val originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, WhoopDatabase::class.java).build()
        try {
            val dao = db.whoopDao()
            dao.upsertDailyMetrics(listOf(DailyMetric("oura-import", "2026-03-08", restingHr = 60),
                DailyMetric("oura-api", "2026-03-08", restingHr = 99)))
            dao.upsertMetricSeries(listOf(MetricSeriesRow("oura-import", "2026-03-08", "rhr", 55.0),
                MetricSeriesRow("oura-import", "2026-03-08", "ref_readiness_score", 82.0)))
            val zone = ZoneId.systemDefault()
            val from = LocalDate.parse("2026-03-08").atStartOfDay(zone).toEpochSecond()
            val to = LocalDate.parse("2026-03-09").atStartOfDay(zone).toEpochSecond()
            val data = OuraDashboardData.load(WhoopRepository(dao), OuraDashboardSource("oura-import", "Import"), from, to, 300)
            val bucket = data.signals.getValue("rhr").buckets.single()
            assertEquals(55.0, bucket.median, 0.0)
            assertEquals(23 * 3600L, bucket.end - bucket.start)
            assertEquals(82.0, data.signals.getValue("ref_readiness_score").mean!!, 0.0)
            assertEquals(0, data.signals.getValue("steps").count)
        } finally { db.close(); TimeZone.setDefault(originalZone) }
    }

    @Test fun chartTapAndAccessibilityActionsInspectValuesWithoutBridgingGaps() {
        val summary = SignalDecimation.reduce(listOf(SignalDecimation.Sample(10, 60.0),
            SignalDecimation.Sample(20, 80.0), SignalDecimation.Sample(650, 90.0)), 0, 900, 300)
        compose.setContent { TestTheme { OuraSignalCard("Test HR", "bpm", "", summary, 0, 900, true) } }
        val chart = compose.onNode(hasContentDescription("Test HR:", substring = true))
        chart.performTouchInput { click(percentOffset(0.15f, 0.5f)) }
        assertTrue(chart.fetchSemanticsNode().config[SemanticsProperties.StateDescription].contains("70.0 bpm"))
        chart.performTouchInput { click(percentOffset(0.5f, 0.5f)) }
        assertFalse(chart.fetchSemanticsNode().config[SemanticsProperties.StateDescription].contains("70.0 bpm"))
        val nextReading = chart.fetchSemanticsNode().config[SemanticsActions.CustomActions].last()
        compose.runOnIdle { assertTrue(nextReading.action()) }
        assertTrue(chart.fetchSemanticsNode().config[SemanticsProperties.StateDescription].contains("70.0 bpm"))
        compose.runOnIdle { assertTrue(nextReading.action()) }
        assertTrue(chart.fetchSemanticsNode().config[SemanticsProperties.StateDescription].contains("90.0 bpm"))
        chart.performTouchInput { swipe(percentOffset(0.8f, 0.5f), percentOffset(0.15f, 0.5f)) }
        assertTrue(chart.fetchSemanticsNode().config[SemanticsProperties.StateDescription].contains("70.0 bpm"))
        captureEvidence("chart-inspection")
    }

    @Test fun controlsSupportLargeTextAndSourceWindowRangeChanges() {
        val source = mutableStateOf("oura-import")
        val days = mutableStateOf(1)
        val ranges = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                TestTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        OuraControls(listOf(OuraDashboardSource("oura-import", "Oura export"),
                            OuraDashboardSource("oura-api", "Oura API history")), source.value,
                            LocalDate.now().minusDays(1), days.value, ranges.value, false,
                            { source.value = it }, {}, { days.value = it }, { ranges.value = it }, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("Oura export").performClick()
        compose.onNodeWithText("Oura API history").performClick()
        assertEquals("oura-api", source.value)
        compose.onNodeWithText("7 days").performScrollTo().performClick()
        assertEquals(7, days.value)
        compose.onNodeWithText("Show ranges").performScrollTo().performClick()
        assertTrue(ranges.value)
        compose.onNodeWithContentDescription("More").performScrollTo().performClick()
        compose.onNodeWithText("Import history").assertIsDisplayed()
        compose.onNodeWithText("Import history").performClick()
        compose.onNodeWithText("Oura API history").performScrollTo()
        captureEvidence("controls-large-text")
    }

    @Test fun sleepTotalsMatchTimelineClippedToEditedSession() {
        val sleep = SleepSession("oura-test", 0, 900, startTsAdjusted = 300,
            stagesJSON = """[{"start":0,"end":1200,"stage":"deep"}]""")
        compose.setContent { TestTheme { OuraSleepCard(sleep) } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Asleep", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Asleep 10 min · Deep 10 · Light 0 · REM 0").assertExists()
        captureEvidence("sleep-clipped-session")
    }

    @Test fun importedSleepMinuteTotalsRemainVisibleWithoutInventingTimeline() {
        val sleep = SleepSession("oura-import", 0, 3600,
            stagesJSON = """{"deep":10,"light":30,"rem":15,"awake":5}""")
        compose.setContent { TestTheme { OuraSleepCard(sleep) } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Asleep", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Asleep 55 min · Deep 10 · Light 30 · REM 15").assertExists()
        compose.onNodeWithText("Stage timeline unavailable for this session.").assertExists()
    }

    @Test fun dashboardLoadsRefreshesAndRestoresWindowControls() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[AppViewModel::class.java]
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent { TestTheme { OuraScreen(vm, {}, {}) } }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Oura export").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("30 days").performScrollTo().performClick()
            compose.onNodeWithText("Show ranges").performScrollTo().performClick()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("30 days").performScrollTo().assertIsSelected()
            compose.onNodeWithText("Show ranges").performScrollTo().assertIsOn()
            compose.waitUntil(10_000) {
                !compose.onNodeWithContentDescription("Refresh").fetchSemanticsNode()
                    .config.contains(SemanticsProperties.Disabled)
            }
            compose.onNodeWithContentDescription("Refresh").performScrollTo().performClick()
            compose.onNodeWithText("Oura export").assertExists()
            captureEvidence("dashboard-empty")
        } finally { compose.runOnIdle { store.clear() } }
    }

    @androidx.compose.runtime.Composable
    private fun TestTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
        NoopTheme {
            // Match AppRoot's Scaffold content color for standalone component tests.
            androidx.compose.material3.Surface(color = Palette.surfaceBase, content = content)
        }
    }

    private fun captureEvidence(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "oura-test-evidence")
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            java.io.File(directory, "$name.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
