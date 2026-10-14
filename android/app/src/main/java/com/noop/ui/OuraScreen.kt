package com.noop.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.SignalDecimation
import com.noop.analytics.SleepStageTotals
import com.noop.data.OuraDashboardData
import com.noop.data.OuraDashboardSource
import com.noop.data.SourceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.json.JSONArray
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

@Composable
fun OuraScreen(vm: AppViewModel, onDevices: () -> Unit, onImport: () -> Unit) {
    val activeId by vm.activeStrapIdFlow.collectAsStateWithLifecycle()
    val recentDays by vm.recentDays.collectAsStateWithLifecycle()
    var sources by remember { mutableStateOf<List<OuraDashboardSource>>(emptyList()) }
    var sourceId by remember { mutableStateOf("") }
    var landedSource by remember { mutableStateOf("") }
    var loadedKey by remember { mutableStateOf("") }
    var day by remember { mutableStateOf(LocalDate.now()) }
    var days by remember { mutableIntStateOf(1) }
    var showRange by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<OuraDashboardData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var sourceMenu by remember { mutableStateOf(false) }
    val source = sources.firstOrNull { it.id == sourceId }
    val zone = ZoneId.systemDefault()
    val from = day.plusDays(1).minusDays(days.toLong()).atStartOfDay(zone).toEpochSecond()
    val to = day.plusDays(1).atStartOfDay(zone).toEpochSecond()
    val bucket = if (days == 1) 300L else if (days == 7) 3600L else 21600L
    val context = LocalContext.current
    val tempUnit = UnitPrefs.temperature(context)
    val loadKey = "$sourceId|$from|$to|$revision"
    val exportLabel = stringResource(R.string.oura_export)
    val apiLabel = stringResource(R.string.oura_api_history)

    LaunchedEffect(sourceId, day, days, revision, activeId, recentDays) {
        loading = true; error = false; data = null
        try {
            val rings = vm.pairedDevices().filter { it.sourceKind == SourceKind.oura.name }
                .map { OuraDashboardSource(it.id, displayName(it)) }
            sources = rings + listOf(OuraDashboardSource("oura-import", exportLabel), OuraDashboardSource("oura-api", apiLabel))
            if (sources.none { it.id == sourceId }) {
                sourceId = sources.firstOrNull { it.id == (activeId ?: vm.activeStrapId) }?.id ?: sources.first().id
                return@LaunchedEffect
            }
            val selected = sources.first { it.id == sourceId }
            if (landedSource != sourceId) {
                val latest = if (selected.isImport) {
                    vm.repo.dailyMetrics(selected.id, "1970-01-01", "9999-12-31").maxOfOrNull { it.day }?.let(LocalDate::parse)
                } else {
                    vm.repo.latestHrSampleTs(selected.id)?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() }
                }
                coroutineContext.ensureActive()
                landedSource = sourceId
                if (latest != null && minOf(latest, LocalDate.now()) != day) {
                    day = minOf(latest, LocalDate.now())
                    return@LaunchedEffect
                }
            }
            val loaded = OuraDashboardData.load(vm.repo, selected, from, to, bucket)
            coroutineContext.ensureActive()
            data = loaded; loadedKey = loadKey; loading = false
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            error = true; loading = false
        }
    }

    ScreenScaffold(title = stringResource(R.string.oura_title), subtitle = stringResource(R.string.oura_subtitle)) {
        Box {
            TextButton(onClick = { sourceMenu = true }) { Text(source?.name ?: stringResource(R.string.oura_source)) }
            DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                sources.forEach { item ->
                    DropdownMenuItem(text = { Text(item.name) }, onClick = { sourceId = item.id; sourceMenu = false })
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { day = day.minusDays(days.toLong()) }) { Text(stringResource(R.string.oura_previous)) }
            TextButton(onClick = {
                DatePickerDialog(context, { _, year, month, date -> day = LocalDate.of(year, month + 1, date) },
                    day.year, day.monthValue - 1, day.dayOfMonth).apply {
                    datePicker.maxDate = System.currentTimeMillis()
                }.show()
            }) {
                Text(day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)), style = NoopType.bodyNumber, color = Palette.textPrimary)
            }
            TextButton(onClick = { day = minOf(day.plusDays(days.toLong()), LocalDate.now()) }, enabled = day < LocalDate.now()) {
                Text(stringResource(R.string.oura_next))
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
            listOf(1, 7, 30).forEach { count ->
                FilterChip(selected = days == count, onClick = { days = count }, label = { Text(stringResource(R.string.oura_days, count)) })
            }
            TextButton(onClick = { day = LocalDate.now() }) { Text(stringResource(R.string.oura_today)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.oura_show_ranges), style = NoopType.body, color = Palette.textPrimary)
            Switch(checked = showRange, onCheckedChange = { showRange = it })
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = onDevices) { Text(stringResource(R.string.oura_manage)) }
            TextButton(onClick = onImport) { Text(stringResource(R.string.oura_import)) }
            TextButton(onClick = { revision++ }, enabled = !loading) { Text(stringResource(R.string.oura_refresh)) }
        }
        if (loading) CircularProgressIndicator(color = Palette.accent)
        else if (error) {
            DataPendingNote(stringResource(R.string.oura_title), stringResource(R.string.oura_load_error))
            TextButton(onClick = { revision++ }) { Text(stringResource(R.string.oura_retry)) }
        } else if (loadedKey == loadKey) data?.let { snapshot ->
            val imported = source?.isImport == true
            Text(stringResource(if (imported) R.string.oura_import_note else R.string.oura_decimation_note),
                style = NoopType.caption, color = Palette.textSecondary)
            if (!imported) Text(stringResource(R.string.oura_bucket_width, bucket / 60), style = NoopType.captionNumber, color = Palette.textSecondary)
            snapshot.signals.values.mapNotNull { it.latestTs }.maxOrNull()?.let { latest ->
                val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(zone)
                Text(stringResource(R.string.oura_latest, formatter.format(Instant.ofEpochSecond(latest))),
                    style = NoopType.caption, color = Palette.textSecondary)
            }
            val keys = if (imported) OuraDashboardData.importedKeys.filter { (snapshot.signals[it]?.count ?: 0) > 0 }.ifEmpty { listOf("rhr") }
                else listOf("hr", "oxygen", "temperature", "hrv", "breathing", "movement", "battery")
            keys.forEach { metric ->
                val (title, unit, note) = ouraPresentation(metric, imported, tempUnit)
                key(sourceId, from, to, metric) {
                    OuraSignalCard(title, unit, note, snapshot.signals[metric] ?: SignalDecimation.Summary(), from, to, showRange) { value ->
                        when {
                            tempUnit != TemperatureUnit.FAHRENHEIT -> value
                            metric == "temperature" -> UnitFormatter.celsiusToFahrenheit(value)
                            metric == "skin_temp_dev_c" -> value * 9 / 5
                            else -> value
                        }
                    }
                }
            }
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                    Text(stringResource(R.string.oura_sleep), style = NoopType.headline, color = Palette.textPrimary)
                    if (snapshot.sleeps.isEmpty()) Text(stringResource(R.string.oura_no_sleep), style = NoopType.caption, color = Palette.textSecondary)
                    snapshot.sleeps.forEach { sleep ->
                        val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(zone)
                        Text(format.format(Instant.ofEpochSecond(sleep.effectiveStartTs)) + " – " + format.format(Instant.ofEpochSecond(sleep.endTs)),
                            style = NoopType.captionNumber, color = Palette.textSecondary)
                        val totals = remember(sleep) {
                            SleepStageTotals.minutes(SleepStageTotals.clampStagesToOnset(sleep.stagesJSON, sleep.effectiveStartTs))
                        }
                        totals?.let {
                            Text(stringResource(R.string.oura_sleep_totals, it.asleep.roundToInt(), it.deep.roundToInt(), it.light.roundToInt(), it.rem.roundToInt()),
                                style = NoopType.captionNumber, color = Palette.textSecondary)
                        }
                        val stages = remember(sleep) {
                            runCatching {
                                val array = JSONArray(sleep.stagesJSON ?: "[]")
                                (0 until array.length()).mapNotNull { index ->
                                    val stage = array.getJSONObject(index)
                                    val start = maxOf(stage.getLong("start"), sleep.effectiveStartTs)
                                    val end = minOf(stage.getLong("end"), sleep.endTs)
                                    val name = stage.getString("stage").let { if (it == "wake") "awake" else it }
                                    if (end > start && name in listOf("awake", "light", "deep", "rem")) PersistedSegment(start, end, name) else null
                                }
                            }.getOrDefault(emptyList())
                        }
                        if (stages.isNotEmpty()) FilledHypnogram(stages, sleep.effectiveStartTs, sleep.endTs, filled = false)
                        else Text(stringResource(R.string.oura_no_stages), style = NoopType.caption, color = Palette.textSecondary)
                    }
                }
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
