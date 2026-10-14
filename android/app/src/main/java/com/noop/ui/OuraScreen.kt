package com.noop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.SignalDecimation
import com.noop.data.OuraDashboardData
import com.noop.data.OuraDashboardSource
import com.noop.data.SourceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.coroutines.coroutineContext

@Composable
fun OuraScreen(vm: AppViewModel, onDevices: () -> Unit, onImport: () -> Unit) {
    val activeId by vm.activeStrapIdFlow.collectAsStateWithLifecycle()
    val recentDays by vm.recentDays.collectAsStateWithLifecycle()
    var sources by remember { mutableStateOf<List<OuraDashboardSource>>(emptyList()) }
    var sourceId by rememberSaveable { mutableStateOf("") }
    var landedSource by rememberSaveable { mutableStateOf("") }
    var dayEpoch by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val day = LocalDate.ofEpochDay(dayEpoch)
    var days by rememberSaveable { mutableIntStateOf(1) }
    var showRange by rememberSaveable { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var loadedKey by remember { mutableStateOf("") }
    var data by remember { mutableStateOf<OuraDashboardData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errorKey by remember { mutableStateOf<String?>(null) }
    val source = sources.firstOrNull { it.id == sourceId }
    val zone = ZoneId.systemDefault()
    val from = day.plusDays(1).minusDays(days.toLong()).atStartOfDay(zone).toEpochSecond()
    val to = day.plusDays(1).atStartOfDay(zone).toEpochSecond()
    val bucket = if (days == 1) 300L else if (days == 7) 3600L else 21600L
    val context = LocalContext.current
    val tempUnit = UnitPrefs.temperature(context)
    // Refresh is deliberately excluded: the current window remains readable while it reloads.
    val windowKey = "$sourceId|$from|$to|$bucket"
    val currentWindow by rememberUpdatedState(windowKey)
    val exportLabel = stringResource(R.string.oura_export)
    val apiLabel = stringResource(R.string.oura_api_history)

    LaunchedEffect(windowKey, revision, activeId, recentDays) {
        loading = true
        errorKey = null
        try {
            // Coalesce rapid date/range taps before starting database work.
            delay(Motion.durationFast.toLong())
            val rings = vm.pairedDevices().filter { it.sourceKind == SourceKind.oura.name }
                .map { OuraDashboardSource(it.id, displayName(it)) }
            coroutineContext.ensureActive()
            if (currentWindow != windowKey) return@LaunchedEffect
            sources = rings + listOf(OuraDashboardSource("oura-import", exportLabel), OuraDashboardSource("oura-api", apiLabel))
            if (sources.none { it.id == sourceId }) {
                sourceId = sources.firstOrNull { it.id == (activeId ?: vm.activeStrapId) }?.id ?: sources.first().id
                return@LaunchedEffect
            }
            val selected = sources.first { it.id == sourceId }
            if (landedSource != selected.id) {
                val latest = if (selected.isImport) {
                    vm.repo.dailyMetrics(selected.id, "1970-01-01", "9999-12-31").maxOfOrNull { it.day }?.let(LocalDate::parse)
                } else {
                    vm.repo.latestHrSampleTs(selected.id)?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() }
                }
                coroutineContext.ensureActive()
                if (currentWindow != windowKey) return@LaunchedEffect
                landedSource = selected.id
                if (latest != null && minOf(latest, LocalDate.now()) != day) {
                    dayEpoch = minOf(latest, LocalDate.now()).toEpochDay()
                    return@LaunchedEffect
                }
            }
            val loaded = OuraDashboardData.load(vm.repo, selected, from, to, bucket)
            coroutineContext.ensureActive()
            if (currentWindow != windowKey) return@LaunchedEffect
            data = loaded
            loadedKey = windowKey
            loading = false
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            if (currentWindow == windowKey) {
                errorKey = windowKey
                loading = false
            }
        }
    }

    val snapshot = data.takeIf { loadedKey == windowKey }
    val failed = errorKey == windowKey
    val imported = source?.isImport == true
    val metricKeys = remember(snapshot, imported) {
        if (imported) OuraDashboardData.importedKeys.filter { (snapshot?.signals?.get(it)?.count ?: 0) > 0 }.ifEmpty { listOf("rhr") }
        else listOf("hr", "oxygen", "temperature", "hrv", "breathing", "movement", "battery")
    }
    val showBackground = remember { NoopPrefs.showDayCycleBackground(context) }
    val skyBehindCards = remember { NoopPrefs.skyBehindCards(context) }
    LazyScreenScaffold(
        title = stringResource(R.string.oura_title), subtitle = stringResource(R.string.oura_subtitle),
        topBackground = screenBackdropSlot(showBackground, skyBehindCards),
        fullBleedBackground = screenBackdropFullBleed(showBackground, skyBehindCards),
    ) {
        item(key = "controls", contentType = "controls") {
            OuraControls(sources, sourceId, day, days, showRange, loading,
                onSource = { sourceId = it }, onDay = { landedSource = sourceId; dayEpoch = it.toEpochDay() },
                onDays = { landedSource = sourceId; days = it }, onRanges = { showRange = it },
                onRefresh = { revision++ }, onDevices = onDevices, onImport = onImport)
        }
        if (failed) item(key = "error", contentType = "status") {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                DataPendingNote(stringResource(R.string.oura_title), stringResource(
                    if (snapshot != null) R.string.oura_refresh_error else R.string.oura_load_error))
                NoopButton(stringResource(R.string.oura_retry), kind = NoopButtonKind.Secondary, onClick = { revision++ })
            }
        }
        if (snapshot == null && !failed) item(key = "loading", contentType = "status") {
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.accent)
                    Text(stringResource(R.string.oura_loading), style = NoopType.body, color = Palette.textSecondary)
                }
            }
        }
        if (snapshot != null) {
            items(metricKeys, key = { "signal:$sourceId:$it" }, contentType = { "signal" }) { metric ->
                val (title, unit, note) = ouraPresentation(metric, imported, tempUnit)
                val fahrenheit = tempUnit == TemperatureUnit.FAHRENHEIT
                OuraSignalCard(title, unit, note, snapshot.signals[metric] ?: SignalDecimation.Summary(), from, to, showRange,
                    valueScale = if (fahrenheit && metric in listOf("temperature", "skin_temp_dev_c")) 1.8 else 1.0,
                    valueOffset = if (fahrenheit && metric == "temperature") 32.0 else 0.0,
                    modifier = Modifier.staggeredAppear(metricKeys.indexOf(metric).coerceAtMost(3)))
            }
            item(key = "explanation", contentType = "explanation") {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                    Text(stringResource(if (imported) R.string.oura_import_note else R.string.oura_decimation_note),
                        style = NoopType.caption, color = Palette.textSecondary)
                    if (!imported) Text(stringResource(R.string.oura_bucket_width, bucket / 60), style = NoopType.captionNumber, color = Palette.textSecondary)
                    snapshot.signals.values.mapNotNull { it.latestTs }.maxOrNull()?.let { latest ->
                        val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(zone)
                        Text(stringResource(R.string.oura_latest, formatter.format(Instant.ofEpochSecond(latest))),
                            style = NoopType.caption, color = Palette.textSecondary)
                    }
                }
            }
            item(key = "sleep-heading", contentType = "heading") {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                    Text(stringResource(R.string.oura_sleep), style = NoopType.headline, color = Palette.textPrimary)
                    if (snapshot.sleeps.isEmpty()) Text(stringResource(R.string.oura_no_sleep), style = NoopType.caption, color = Palette.textSecondary)
                }
            }
            items(snapshot.sleeps, key = { "sleep:${it.deviceId}:${it.startTs}" }, contentType = { "sleep" }) { sleep ->
                OuraSleepCard(sleep)
            }
        }
    }
}

@Composable
private fun ouraPresentation(key: String, imported: Boolean, temp: TemperatureUnit): Triple<String, String, String> {
    val title = when (key) {
        "hr" -> R.string.oura_hr; "oxygen" -> R.string.oura_oxygen; "temperature" -> R.string.oura_temperature
        "hrv" -> R.string.oura_hrv; "breathing" -> R.string.oura_breathing; "movement" -> R.string.oura_movement
        "battery" -> R.string.oura_battery; "rhr" -> R.string.oura_rhr; "spo2" -> R.string.oura_spo2
        "resp_rate" -> R.string.oura_imported_resp
        "skin_temp_dev_c" -> R.string.oura_temp_deviation; "steps" -> R.string.oura_steps
        "energy_kcal" -> R.string.oura_energy; "total_kcal" -> R.string.oura_total_energy
        "sleep_total_min" -> R.string.oura_sleep_duration; "sleep_deep_min" -> R.string.oura_deep
        "sleep_rem_min" -> R.string.oura_rem; "ref_readiness_score" -> R.string.oura_readiness
        "ref_sleep_score" -> R.string.oura_sleep_score; "ref_activity_score" -> R.string.oura_activity_score; else -> R.string.oura_vo2
    }
    val unit = when (key) {
        "hr", "rhr" -> "bpm"; "oxygen", "spo2", "battery" -> "%"; "hrv" -> "ms"
        "temperature", "skin_temp_dev_c" -> UnitFormatter.temperatureUnit(temp)
        "breathing", "resp_rate" -> stringResource(R.string.oura_breaths_unit); "movement" -> "s"
        "steps" -> stringResource(R.string.oura_steps_unit); "energy_kcal", "total_kcal" -> "kcal"
        "sleep_total_min", "sleep_deep_min", "sleep_rem_min" -> "min"
        "ref_readiness_score", "ref_sleep_score", "ref_activity_score" -> "/100"; else -> "mL/kg/min"
    }
    val note = when {
        key == "oxygen" -> stringResource(R.string.oura_oxygen_note)
        key == "hrv" && !imported -> stringResource(R.string.oura_hrv_note)
        key == "breathing" -> stringResource(R.string.oura_breathing_note)
        key == "movement" -> stringResource(R.string.oura_movement_note)
        else -> ""
    }
    return Triple(stringResource(title), unit, note)
}
