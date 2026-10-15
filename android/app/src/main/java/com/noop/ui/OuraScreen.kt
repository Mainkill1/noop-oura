package com.noop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.noop.R
import com.noop.data.OuraDashboardData
import com.noop.data.OuraDashboardSource
import com.noop.data.SourceKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.coroutineContext

private data class OuraLoadedWindow(val key: String, val from: Long, val to: Long, val bucket: Long, val data: OuraDashboardData)

@Composable
fun OuraScreen(vm: AppViewModel, onDevices: () -> Unit, onImport: () -> Unit, onSettings: () -> Unit) {
    var showKeyTransfer by remember { mutableStateOf(false) }
    if (showKeyTransfer) OuraKeyTransferDialog(onDismiss = { showKeyTransfer = false })
    val activeId by vm.activeStrapIdFlow.collectAsStateWithLifecycle()
    val activeIsOura by vm.activeIsOura.collectAsStateWithLifecycle()
    val ringLinkPhase by vm.ouraLinkPhase.collectAsStateWithLifecycle()
    val liveSession by vm.ouraLiveSession.collectAsStateWithLifecycle()
    val historyPhase by vm.ouraHistorySyncPhase.collectAsStateWithLifecycle()
    val recentDays by vm.recentDays.collectAsStateWithLifecycle()
    var sources by remember { mutableStateOf<List<OuraDashboardSource>>(emptyList()) }
    var sourceId by rememberSaveable { mutableStateOf("") }
    var landedSource by rememberSaveable { mutableStateOf("") }
    var dayEpoch by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val day = LocalDate.ofEpochDay(dayEpoch)
    var days by rememberSaveable { mutableIntStateOf(1) }
    var mode by remember { mutableStateOf(OuraViewMode.History) }
    var category by rememberSaveable { mutableStateOf(OuraCategory.Health) }
    var showRange by rememberSaveable { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf<OuraLoadedWindow?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errorKey by remember { mutableStateOf<String?>(null) }
    var elapsedNow by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val source = sources.firstOrNull { it.id == sourceId }
    val imported = source?.isImport == true
    val isLive = mode == OuraViewMode.Live
    val liveEligible = activeIsOura && source != null && !imported && sourceId == activeId
    val zone = ZoneId.systemDefault()
    val historyFrom = day.plusDays(1).minusDays(days.toLong()).atStartOfDay(zone).toEpochSecond()
    val historyTo = day.plusDays(1).atStartOfDay(zone).toEpochSecond()
    val historyBucket = if (days == 1) 300L else if (days == 7) 3600L else 21600L
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val tempUnit = UnitPrefs.temperature(context)
    val windowKey = if (isLive) "$sourceId|live" else "$sourceId|$historyFrom|$historyTo|$historyBucket"
    val currentWindow by rememberUpdatedState(windowKey)
    val exportLabel = stringResource(R.string.oura_export)
    val apiLabel = stringResource(R.string.oura_api_history)
    var comparisonCsv by rememberSaveable(sourceId) {
        mutableStateOf(if (imported) "rhr,hrv,skin_temp_dev_c" else "hr,temperature,movement")
    }

    var pendingStart by remember { mutableStateOf(false) }
    val owner = remember(vm) {
        OuraLiveOwner { intent ->
            if (intent.action == OuraLiveAction.Start) pendingStart = true
            if (intent.action == OuraLiveAction.Stop) pendingStart = false
            when (intent.action) {
                OuraLiveAction.Start -> vm.startOuraLiveSession(intent.selection, intent.sourceId, intent.generation)
                OuraLiveAction.Update -> vm.updateOuraLiveSession(intent.selection, intent.sourceId, intent.generation)
                OuraLiveAction.Stop -> vm.stopOuraLiveSession(intent.sourceId, intent.generation)
            }
        }
    }
    SideEffect {
        owner.observe(liveSession)
        pendingStart = owner.hasOwnership && !liveSession.active
    }
    // Monotonic display clock never creates or requests samples.
    LaunchedEffect(isLive, lifecycle) {
        if (isLive) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { elapsedNow = SystemClock.elapsedRealtime(); delay(100) }
        }
    }

    LaunchedEffect(windowKey, revision, activeId, activeIsOura, if (isLive) null else recentDays, lifecycle, isLive, category, historyPhase == com.noop.ble.OuraHistorySyncPhase.IDLE) {
        var selected: OuraDashboardSource? = null
        refreshOuraWindow(lifecycle, false, windowKey, { currentWindow }, prepare = {
            loading = true
            errorKey = null
            delay(Motion.durationFast.toLong())
            val rings = vm.pairedDevices().filter { it.sourceKind == SourceKind.oura.name }
                .map { OuraDashboardSource(it.id, displayName(it)) }
            coroutineContext.ensureActive()
            if (currentWindow != windowKey) return@refreshOuraWindow false
            sources = rings + listOf(OuraDashboardSource("oura-import", exportLabel), OuraDashboardSource("oura-api", apiLabel))
            if (sources.none { it.id == sourceId }) {
                sourceId = sources.firstOrNull { it.id == (activeId ?: vm.activeStrapId) }?.id ?: sources.first().id
                return@refreshOuraWindow false
            }
            val choice = sources.first { it.id == sourceId }
            selected = choice
            if (isLive && category != OuraCategory.Health) {
                loading = false
                return@refreshOuraWindow false
            }
            if (!isLive && landedSource != choice.id) {
                val latest = if (choice.isImport) {
                    vm.repo.dailyMetrics(choice.id, "1970-01-01", "9999-12-31").maxOfOrNull { it.day }?.let(LocalDate::parse)
                } else vm.repo.latestHrSampleTs(choice.id)?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() }
                coroutineContext.ensureActive()
                if (currentWindow != windowKey) return@refreshOuraWindow false
                landedSource = choice.id
                if (latest != null && minOf(latest, LocalDate.now()) != day) {
                    dayEpoch = minOf(latest, LocalDate.now()).toEpochDay()
                    return@refreshOuraWindow false
                }
            }
            true
        }, load = {
            val (from, to) = historyFrom to historyTo
            val bucket = historyBucket
            OuraLoadedWindow(windowKey, from, to, bucket,
                OuraDashboardData.load(vm.repo, requireNotNull(selected), from, to, bucket))
        }, publish = {
            loaded = it
            loading = false
            errorKey = null
        }, onError = {
            loading = false
            errorKey = windowKey
        })
    }

    val window = loaded?.takeIf { it.key == windowKey && (!isLive || liveEligible) }
    val snapshot = window?.data
    val displaySignals = remember(snapshot) { ouraRecordedDisplaySignals(snapshot?.signals.orEmpty()) }
    val failed = errorKey == windowKey
    val metricKeys = ouraCategoryKeys(imported, category)
    val allKeys = ouraCategoryKeys(imported, OuraCategory.Compare)
    val comparison = comparisonCsv.split(',').filter { it in allKeys }.distinct().take(4)
    var liveComparisonCsv by rememberSaveable { mutableStateOf("hr,x,y,z") }
    val liveComparison = liveComparisonCsv.split(',').filter { it in listOf("hr", "x", "y", "z") }.toSet()
    val visibleSession = ouraLiveSnapshotForSource(liveSession, sourceId)
    OuraLiveLifecycle(owner, lifecycle, sourceId, isLive, liveSession,
        ready = liveEligible && liveSession.sourceDeviceId == sourceId &&
            ringLinkPhase == com.noop.ble.OuraLiveSource.LinkPhase.AUTHENTICATED,
        selection = ouraLiveSelection(category, liveComparison))
    fun changeCategory(next: OuraCategory) {
        category = next
    }
    val showBackground = remember { NoopPrefs.showDayCycleBackground(context) }
    val skyBehindCards = remember { NoopPrefs.skyBehindCards(context) }
    LazyScreenScaffold(
        title = null, subtitle = null, rowSpacing = Metrics.space8,
        topBackground = screenBackdropSlot(showBackground, skyBehindCards),
        fullBleedBackground = screenBackdropFullBleed(showBackground, skyBehindCards),
    ) {
        item(key = "oura-header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.oura_title), Modifier.weight(1f), style = NoopType.headline, color = Palette.textPrimary)
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.nav_settings)) }
            }
        }
        item(key = "navigation", contentType = "navigation") {
            OuraExplorerNavigation(mode, category, { if (it != mode) owner.hide(); mode = it }, ::changeCategory)
        }
        item(key = "controls", contentType = "controls") {
            OuraControls(sources, sourceId, day, days, showRange, loading,
                onSource = { if (sourceId != it) owner.hide(); sourceId = it }, onDay = { landedSource = sourceId; dayEpoch = it.toEpochDay() },
                onDays = { landedSource = sourceId; days = it }, onRanges = { showRange = it },
                onRefresh = { revision++ }, onDevices = onDevices, onImport = onImport,
                onKeys = { showKeyTransfer = true }, isLive = isLive)
        }
        if (isLive) item(key = "live-panel", contentType = "live") {
            OuraLivePanel(visibleSession, elapsedNow, category, liveComparison,
                eligible = liveEligible && liveSession.sourceDeviceId == sourceId,
                connected = ringLinkPhase == com.noop.ble.OuraLiveSource.LinkPhase.AUTHENTICATED,
                historyPhase = historyPhase,
                onComparison = {
                    liveComparisonCsv = it.joinToString(",")
                }, onHistory = { owner.hide(); mode = OuraViewMode.History }, pendingStart = pendingStart)
        }
        if (isLive && category == OuraCategory.Health && snapshot != null) item(key = "recorded-health") {
            OuraRecordedHealth(snapshot, tempUnit, onHistory = { owner.hide(); mode = OuraViewMode.History })
        }
        if (failed) item(key = "error", contentType = "status") {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                DataPendingNote(stringResource(R.string.oura_title), stringResource(
                    if (snapshot != null) R.string.oura_refresh_error else R.string.oura_load_error))
                NoopButton(stringResource(R.string.oura_retry), kind = NoopButtonKind.Secondary, onClick = { revision++ })
            }
        }
        if (!isLive && snapshot == null && !failed) item(key = "loading", contentType = "status") {
            OuraPanel {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.accent)
                    Text(stringResource(R.string.oura_loading), style = NoopType.body, color = Palette.textSecondary)
                }
            }
        }
        if (!isLive && snapshot != null) {
            if (category == OuraCategory.Compare) item(key = "compare:$sourceId", contentType = "compare") {
                OuraComparisonCard(allKeys, displaySignals, window.from, window.to, imported, tempUnit, comparison,
                    { comparisonCsv = it.joinToString(",") })
            } else {
                if (metricKeys.isNotEmpty()) item(key = "overview:$category", contentType = "overview") {
                    OuraStatsOverview(snapshot, metricKeys, imported, tempUnit, isLive = false)
                }
                if (category == OuraCategory.Sleep) {
                    if (snapshot.sleeps.isEmpty()) item(key = "no-sleep", contentType = "status") {
                        Text(stringResource(R.string.oura_no_sleep), style = NoopType.body, color = Palette.textSecondary)
                    }
                    items(snapshot.sleeps, key = { "sleep:${it.deviceId}:${it.startTs}" }, contentType = { "sleep" }) { OuraSleepCard(it) }
                }
                if (category == OuraCategory.Movement && !imported) item(key = "motion-details", contentType = "motion") {
                    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                        OuraLiveMotionDetails(snapshot.liveMotionSeconds)
                        OuraMovementDetails(snapshot.motionWindows)
                    }
                }
                if (metricKeys.any { (snapshot.signals[it]?.count ?: 0) > 0 }) item(key = "charts-heading", contentType = "heading") {
                    Text(stringResource(R.string.oura_chart_details), style = NoopType.headline, color = Palette.textPrimary)
                }
                items(metricKeys.filter { (snapshot.signals[it]?.count ?: 0) > 0 }, key = { "signal:$sourceId:$it" }, contentType = { "signal" }) { metric ->
                    val (title, unit, note) = ouraPresentation(metric, imported, tempUnit)
                    val (scale, offset) = ouraValueTransform(metric, tempUnit)
                    OuraSignalCard(title, unit, note, displaySignals.getValue(metric), window.from, window.to, showRange || metric.startsWith("live_motion_"),
                        valueScale = scale, valueOffset = offset)
                }
            }
            item(key = "explanation", contentType = "explanation") {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                    Text(stringResource(if (imported) R.string.oura_import_note else R.string.oura_decimation_note),
                        style = NoopType.caption, color = Palette.textSecondary)
                    if (!imported) Text(stringResource(R.string.oura_bucket_width_seconds, window.bucket), style = NoopType.captionNumber, color = Palette.textSecondary)
                }
            }
        }
    }
}

/** Every supported metric stays on the same dashboard; missing observations remain explicitly blank. */
@Composable
internal fun OuraStatsOverview(data: OuraDashboardData, metricKeys: List<String>, imported: Boolean, temp: TemperatureUnit,
    isLive: Boolean = false) {
    val timestamp = rememberOuraTimestampFormatter()
    val columns = if (LocalDensity.current.fontScale >= 1.5f) 1 else 2
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Text(stringResource(R.string.oura_overview), style = NoopType.subhead, color = Palette.textPrimary)
        Text(stringResource(if (isLive) R.string.oura_latest_recorded_info else R.string.oura_overview_range), style = NoopType.caption, color = Palette.textSecondary)
        metricKeys.chunked(columns).forEach { metrics ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                metrics.forEach { metric ->
                    val (title, unit, note) = ouraPresentation(metric, imported, temp)
                    val summary = data.signals[metric]
                    val (scale, offset) = ouraValueTransform(metric, temp)
                    val value = (if (isLive) summary?.latestValue else summary?.mean)?.let { String.format(java.util.Locale.getDefault(), "%.1f", it * scale + offset) }
                    OuraPanel(Modifier.weight(1f)) {
                        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                            Text(title, style = NoopType.subhead, color = Palette.textSecondary)
                            Text(value ?: "—", style = NoopType.chartValueLarge,
                                color = if (value != null) Palette.textPrimary else Palette.textTertiary)
                            Text(unit, style = NoopType.captionNumber, color = Palette.textSecondary)
                            if (value != null) {
                                val latest = summary?.latestTs
                                val caption = if (isLive && latest != null) {
                                    stringResource(R.string.oura_latest, timestamp.format(Instant.ofEpochSecond(latest)))
                                } else stringResource(R.string.explore_average)
                                Text(caption, style = NoopType.caption, color = Palette.textTertiary)
                            }
                            if (note.isNotEmpty()) Text(note, style = NoopType.caption, color = Palette.textSecondary)
                        }
                    }
                }
                if (metrics.size < columns) Spacer(Modifier.weight(1f))
            }
        }
    }
}

internal fun ouraValueTransform(metric: String, temp: TemperatureUnit): Pair<Double, Double> {
    val fahrenheit = temp == TemperatureUnit.FAHRENHEIT
    return (if (fahrenheit && metric in listOf("temperature", "skin_temp_dev_c")) 1.8 else 1.0) to
        (if (fahrenheit && metric == "temperature") 32.0 else 0.0)
}

@Composable
internal fun ouraPresentation(key: String, imported: Boolean, temp: TemperatureUnit): Triple<String, String, String> {
    val title = when (key) {
        "live_motion_x" -> R.string.oura_host_x; "live_motion_y" -> R.string.oura_host_y; "live_motion_z" -> R.string.oura_host_z
        "live_motion_x_min", "live_motion_y_min", "live_motion_z_min" -> R.string.oura_host_min
        "live_motion_x_max", "live_motion_y_max", "live_motion_z_max" -> R.string.oura_host_max
        "live_motion_count" -> R.string.oura_host_count
        "hr" -> R.string.oura_hr; "oxygen" -> R.string.oura_oxygen; "temperature" -> R.string.oura_temperature
        "hrv" -> R.string.oura_hrv; "breathing" -> R.string.oura_breathing; "movement" -> R.string.oura_movement
        "motion_x" -> R.string.oura_motion_x; "motion_y" -> R.string.oura_motion_y; "motion_z" -> R.string.oura_motion_z
        "motion_low" -> R.string.oura_motion_low; "motion_high" -> R.string.oura_motion_high
        "battery" -> R.string.oura_battery; "rhr" -> R.string.oura_rhr; "spo2" -> R.string.oura_spo2
        "resp_rate" -> R.string.oura_imported_resp
        "skin_temp_dev_c" -> R.string.oura_temp_deviation; "steps" -> R.string.oura_steps
        "energy_kcal" -> R.string.oura_energy; "total_kcal" -> R.string.oura_total_energy
        "sleep_total_min" -> R.string.oura_sleep_duration; "sleep_deep_min" -> R.string.oura_deep
        "sleep_rem_min" -> R.string.oura_rem; "ref_readiness_score" -> R.string.oura_readiness
        "ref_sleep_score" -> R.string.oura_sleep_score; "ref_activity_score" -> R.string.oura_activity_score; else -> R.string.oura_vo2
    }
    val unit = when {
        key.startsWith("live_motion_") -> stringResource(if (key == "live_motion_count") R.string.oura_vectors_unit else R.string.oura_live_raw_counts)
        else -> when (key) {
        "hr", "rhr" -> "bpm"; "oxygen", "spo2", "battery" -> "%"; "hrv" -> "ms"
        "temperature", "skin_temp_dev_c" -> UnitFormatter.temperatureUnit(temp)
        "breathing", "resp_rate" -> stringResource(R.string.oura_breaths_unit); "movement" -> "s"
        "motion_x", "motion_y", "motion_z", "motion_low", "motion_high" -> stringResource(R.string.oura_raw_unit)
        "steps" -> stringResource(R.string.oura_steps_unit); "energy_kcal", "total_kcal" -> "kcal"
        "sleep_total_min", "sleep_deep_min", "sleep_rem_min" -> "min"
        "ref_readiness_score", "ref_sleep_score", "ref_activity_score" -> "/100"; else -> "mL/kg/min"
    }
    }
    val note = when {
        key.startsWith("live_motion_") -> stringResource(R.string.oura_host_motion_info)
        key == "oxygen" -> stringResource(R.string.oura_oxygen_note)
        key == "hrv" && !imported -> stringResource(R.string.oura_hrv_note)
        key == "breathing" -> stringResource(R.string.oura_breathing_note)
        key == "movement" -> stringResource(R.string.oura_movement_note)
        else -> ""
    }
    return Triple(stringResource(title), unit, note)
}
