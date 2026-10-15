package com.noop.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import com.noop.R
import com.noop.ble.*
import java.util.Locale
import kotlin.math.abs

private data class OuraLiveTrace(val key: String, val label: String, val unit: String, val color: Color,
    val raw: List<OuraLivePoint>, val display: List<OuraLivePoint>, val gapMs: Long,
    val minimum: Double, val maximum: Double)

/** Production live surface: direct in-memory samples, independent of a database, Activity, or Bluetooth. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OuraLivePanel(snapshot: OuraLiveSessionSnapshot, nowElapsedMs: Long, category: OuraCategory,
    comparison: Set<String>, eligible: Boolean, connected: Boolean, historyPhase: OuraHistorySyncPhase,
    onStart: () -> Unit, onStop: () -> Unit, onComparison: (Set<String>) -> Unit, onHistory: () -> Unit,
    pendingStart: Boolean = false) {
    var windowSeconds by rememberSaveable { mutableIntStateOf(30) }
    var smoothed by rememberSaveable { mutableStateOf(false) }
    var axesCsv by rememberSaveable { mutableStateOf("x,y,z") }
    val axes = axesCsv.split(',').filter { it.isNotEmpty() }.toSet()
    val desired = ouraLiveSelection(category, comparison)
    val hrRaw = remember(snapshot.hr.samples) { snapshot.hr.samples.map { OuraLivePoint(it.receivedAtElapsedMs, it.bpm.toDouble()) } }
    val hrTrend = remember(hrRaw, smoothed) { if (smoothed) ouraLiveHrTrend(hrRaw) else hrRaw }
    val motionRaw = remember(snapshot.motion.samples) {
        listOf("x", "y", "z").associateWith { key -> snapshot.motion.samples.map { sample ->
            OuraLivePoint(sample.receivedAtElapsedMs, when (key) { "x" -> sample.x; "y" -> sample.y; else -> sample.z }.toDouble())
        } }
    }
    var motionExtent by remember(snapshot.sourceDeviceId, snapshot.startedAtElapsedMs) { mutableDoubleStateOf(1024.0) }
    val observedExtent = remember(motionRaw) { motionRaw.values.flatten().maxOfOrNull { abs(it.value) } ?: 0.0 }
    SideEffect { if (observedExtent > motionExtent) motionExtent = observedExtent }
    val extent = maxOf(motionExtent, observedExtent)
    val rawUnit = stringResource(R.string.oura_live_raw_counts)
    val keys = when (category) {
        OuraCategory.Health -> setOf("hr")
        OuraCategory.Movement -> axes
        OuraCategory.Compare -> comparison
        OuraCategory.Sleep -> emptySet()
    }
    val colors = listOf(Palette.accent, Palette.metricRose, Palette.metricAmber, Palette.metricPurple)
    val allTraces = listOf("hr", "x", "y", "z").mapIndexed { index, key ->
        val label = stringResource(when (key) { "hr" -> R.string.oura_hr; "x" -> R.string.oura_motion_x; "y" -> R.string.oura_motion_y; else -> R.string.oura_motion_z })
        if (key == "hr") OuraLiveTrace(key, label, "bpm", colors[index], hrRaw, hrTrend, 5000,
            minOf(40.0, hrRaw.minOfOrNull { it.value } ?: 40.0), maxOf(120.0, hrRaw.maxOfOrNull { it.value } ?: 120.0))
        else OuraLiveTrace(key, label, rawUnit, colors[index], motionRaw.getValue(key), motionRaw.getValue(key), 1500, -extent, extent)
    }
    val traces = allTraces.filter { it.key in keys }
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Text(stringResource(when { pendingStart -> R.string.oura_live_starting; snapshot.active -> R.string.oura_live_running; else -> R.string.oura_live_stopped }),
                style = NoopType.headline, color = Palette.textPrimary, modifier = Modifier.testTag("oura-live-session"))
            Text(stringResource(when {
                !eligible -> R.string.oura_live_requires_ring
                !connected -> R.string.oura_live_disconnected
                else -> R.string.oura_live_connected
            }), style = NoopType.caption, color = Palette.textSecondary)
            Text(stringResource(when (historyPhase) {
                OuraHistorySyncPhase.SYNCING -> R.string.oura_live_history_syncing
                OuraHistorySyncPhase.PAUSED_FOR_LIVE -> R.string.oura_live_history_paused
                OuraHistorySyncPhase.IDLE -> R.string.oura_live_history_idle
            }), style = NoopType.caption, color = Palette.textSecondary)
            if (category == OuraCategory.Sleep) {
                Text(stringResource(R.string.oura_sleep_history), style = NoopType.body, color = Palette.textSecondary)
                NoopButton(stringResource(R.string.oura_history), kind = NoopButtonKind.Secondary, onClick = onHistory)
            } else {
                if (desired.motion) Text(stringResource(R.string.oura_live_motion_limit), style = NoopType.caption, color = Palette.textSecondary)
                if (snapshot.active && snapshot.motionDeadlineElapsedMs != null) Text(
                    pluralStringResource(R.plurals.oura_live_remaining_seconds,
                        ((snapshot.motionDeadlineElapsedMs - nowElapsedMs).coerceAtLeast(0) / 1000).toInt(),
                        ((snapshot.motionDeadlineElapsedMs - nowElapsedMs).coerceAtLeast(0) / 1000)),
                    style = NoopType.captionNumber, color = Palette.textSecondary)
                NoopButton(stringResource(if (snapshot.active || pendingStart) R.string.oura_live_stop else R.string.oura_live_start),
                    enabled = snapshot.active || pendingStart || (eligible && connected && (desired.hr || desired.motion)),
                    modifier = Modifier.testTag("oura-live-start-stop"),
                    onClick = if (snapshot.active || pendingStart) onStop else onStart)
                if (category == OuraCategory.Compare || category == OuraCategory.Movement) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Metrics.space8), verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                        allTraces.filter { category == OuraCategory.Compare || it.key != "hr" }.forEach { trace ->
                            val selected = trace.key in keys
                            FilterChip(selected = selected, onClick = {
                                val changed = if (selected) keys - trace.key else keys + trace.key
                                if (category == OuraCategory.Compare) onComparison(changed) else axesCsv = changed.joinToString(",")
                            }, label = { Text(trace.label, style = NoopType.caption) }, modifier = Modifier.testTag("oura-live-trace-${trace.key}"))
                        }
                    }
                }
                // Status follows actual accepted workload, independently of desired tab/axis visibility.
                if ("hr" in keys || snapshot.selection.hr) OuraLiveStreamReadout(allTraces.first(), snapshot.active, connected,
                    snapshot.hr.phase, snapshot.hr.lastReceiptElapsedMs, snapshot.hr.observedRateHz, nowElapsedMs)
                if (category == OuraCategory.Movement || keys.any { it != "hr" } || snapshot.selection.motion) OuraLiveStreamReadout(
                    allTraces[1].copy(label = stringResource(R.string.oura_tab_movement), raw = emptyList()), snapshot.active,
                    connected, snapshot.motion.phase, snapshot.motion.lastReceiptElapsedMs, snapshot.motion.observedRateHz, nowElapsedMs)
                if ("hr" in keys) {
                    val smoothLabel = stringResource(R.string.oura_live_smoothed)
                    val rawLabel = stringResource(R.string.oura_live_raw)
                    SegmentedPillControl(items = listOf(false, true), selection = smoothed,
                        label = { if (it) smoothLabel else rawLabel },
                        onSelect = { smoothed = it }, modifier = Modifier.fillMaxWidth(), adaptsToAvailableWidth = true)
                    Text(stringResource(R.string.oura_live_trend_info), style = NoopType.caption, color = Palette.textSecondary)
                }
                val context = androidx.compose.ui.platform.LocalContext.current
                SegmentedPillControl(items = listOf(10, 30, 60), selection = windowSeconds,
                    label = { context.getString(R.string.oura_live_window_seconds, it) }, onSelect = { windowSeconds = it },
                    modifier = Modifier.fillMaxWidth(), adaptsToAvailableWidth = true,
                    accessibilityLabel = stringResource(R.string.oura_window))
                traces.groupBy { it.unit }.values.forEach { group ->
                    Text(group.joinToString(" / ") { it.label } + ": " + formatLive(group.first().minimum) + " … " +
                        formatLive(group.first().maximum) + " " + group.first().unit,
                        style = NoopType.captionNumber, color = Palette.textSecondary)
                }
                OuraLivePlot(traces, ouraLivePlotEnd(snapshot, nowElapsedMs), windowSeconds,
                    nowElapsedMs, snapshot.sourceDeviceId to snapshot.startedAtElapsedMs)
                Text(stringResource(R.string.oura_live_plot_info), style = NoopType.caption, color = Palette.textTertiary)
            }
        }
    }
}

@Composable
private fun OuraLiveStreamReadout(trace: OuraLiveTrace, active: Boolean, connected: Boolean,
    phase: OuraLiveStreamPhase, receipt: Long?, rate: Double?, now: Long) {
    val status = ouraLiveReadingState(active, connected, phase, receipt, now, trace.gapMs)
    val label = stringResource(when (status) {
        OuraLiveReadingState.Waiting -> R.string.oura_live_waiting_samples
        OuraLiveReadingState.Receiving -> R.string.oura_live_receiving
        OuraLiveReadingState.Stale -> R.string.oura_live_no_new_samples
        OuraLiveReadingState.Unavailable -> R.string.oura_live_unavailable
        OuraLiveReadingState.Disconnected -> R.string.oura_live_disconnected
        OuraLiveReadingState.Stopped -> R.string.oura_live_stopped
    })
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
        Text(trace.label + " · " + label, style = NoopType.subhead, color = trace.color)
        if (trace.key == "hr") Text(trace.raw.lastOrNull()?.let { formatLive(it.value) + " " + trace.unit } ?: "—",
            style = NoopType.title2, color = Palette.textPrimary)
        val age = ouraLiveAgeSeconds(receipt, now)
        if (age != null) Text(pluralStringResource(R.plurals.oura_live_received_seconds, age.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), age),
            style = NoopType.captionNumber, color = Palette.textSecondary, modifier = Modifier.testTag("oura-live-age-${trace.key}"))
        if (rate != null && active) Text(stringResource(R.string.oura_live_observed_rate, formatLive(rate)),
            style = NoopType.captionNumber, color = Palette.textSecondary)
    }
}

private fun formatLive(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OuraLivePlot(traces: List<OuraLiveTrace>, end: Long, seconds: Int, now: Long, sessionKey: Pair<String?, Long?>) {
    var cursor by remember(sessionKey) { mutableStateOf<Long?>(null) }
    val from = end - seconds * 1000L
    val currentFrom by rememberUpdatedState(from)
    val colors = traces.map { it.color }
    val title = stringResource(R.string.oura_compare_title)
    val previous = stringResource(R.string.oura_previous_reading)
    val next = stringResource(R.string.oura_next_reading)
    val anchors = remember(traces) { traces.flatMap { it.raw.map(OuraLivePoint::elapsedMs) }.distinct().sorted() }
    val inspection = traces.map { trace ->
        val values = cursor?.let { ouraLiveInspect(trace.raw, it, trace.gapMs) }.orEmpty()
        trace.label + ": " + if (values.isEmpty()) "—" else values.joinToString(" / ") { formatLive(it.value) + " " + trace.unit }
    }
    Spacer(Modifier.fillMaxWidth().height(Metrics.largeChartHeight).testTag("oura-live-plot").semantics {
        contentDescription = title
        stateDescription = inspection.joinToString()
        customActions = listOf(
            CustomAccessibilityAction(previous) {
                val target = if (cursor == null) anchors.lastOrNull() else anchors.lastOrNull { it < cursor!! }
                if (target == null) false else { cursor = target; true }
            },
            CustomAccessibilityAction(next) {
                val target = if (cursor == null) anchors.firstOrNull() else anchors.firstOrNull { it > cursor!! }
                if (target == null) false else { cursor = target; true }
            })
    }.pointerInput(sessionKey, seconds) {
        detectTapGestures { cursor = currentFrom + (it.x / size.width.coerceAtLeast(1) * seconds * 1000).toLong() }
    }.pointerInput(sessionKey, seconds) {
        fun inspect(x: Float) { cursor = currentFrom + (x / size.width.coerceAtLeast(1) * seconds * 1000).toLong() }
        detectHorizontalDragGestures(onDragStart = { inspect(it.x) }) { change, _ -> change.consume(); inspect(change.position.x) }
    }.drawWithCache {
        fun x(time: Long) = ((time - from).toDouble() / (seconds * 1000) * size.width).toFloat()
        val strokeWidth = Metrics.space2.toPx()
        val paths = traces.map { trace ->
            ouraLiveSegments(trace.display, from, end, size.width.toInt().coerceAtLeast(1), trace.gapMs).map { segment ->
                val path = Path()
                segment.forEachIndexed { index, point ->
                    val px = x(point.elapsedMs)
                    val py = (size.height * (1 - (point.value - trace.minimum) / (trace.maximum - trace.minimum))).toFloat()
                    if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    if (segment.size == 1) path.lineTo(px + strokeWidth, py)
                }
                path
            }
        }
        val dashUnit = Metrics.space4.toPx()
        onDrawBehind {
            clipRect {
                for (fraction in listOf(0f, .5f, 1f)) drawLine(Palette.hairline, Offset(0f, fraction * size.height), Offset(size.width, fraction * size.height))
                paths.forEachIndexed { index, segments ->
                    val key = traces[index].key
                    val effect = when (key) { "y" -> PathEffect.dashPathEffect(floatArrayOf(dashUnit * 2, dashUnit)); "z" -> PathEffect.dashPathEffect(floatArrayOf(dashUnit, dashUnit)); else -> null }
                    segments.forEach { drawPath(it, colors[index], style = Stroke(strokeWidth, pathEffect = effect)) }
                }
                cursor?.let { drawLine(Palette.textSecondary, Offset(x(it), 0f), Offset(x(it), size.height), strokeWidth) }
            }
        }
    })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.oura_live_relative_seconds, seconds), style = NoopType.captionNumber, color = Palette.textSecondary)
        Text(stringResource(R.string.oura_live_window_end), style = NoopType.caption, color = Palette.textSecondary)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Metrics.space12), verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
        traces.forEach { trace -> Text(trace.label + (if (trace.key == "hr") " · " else ": " + (trace.raw.lastOrNull()?.let { formatLive(it.value) } ?: "—") + " ") + trace.unit, style = NoopType.captionNumber, color = trace.color) }
    }
    if (cursor != null) {
        val age = ouraLiveAgeSeconds(cursor, now) ?: 0
        Text(pluralStringResource(R.plurals.oura_live_received_seconds, age.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), age),
            style = NoopType.captionNumber, color = Palette.textSecondary)
        inspection.forEach { Text(it, style = NoopType.captionNumber, color = Palette.textPrimary) }
    }
}
