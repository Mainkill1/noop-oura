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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import com.noop.R
import com.noop.analytics.OuraComparison
import com.noop.analytics.SignalDecimation
import java.time.Instant
import java.util.Locale

private data class OuraCompareTrace(val trace: OuraComparison.Trace, val label: String, val note: String, val color: Color)

/** Same-time overlays with explicit unit domains; no interpolation across missing buckets. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OuraComparisonCard(keys: List<String>, signals: Map<String, SignalDecimation.Summary>,
    from: Long, to: Long, imported: Boolean, temp: TemperatureUnit,
    selected: List<String>, onSelect: (List<String>) -> Unit) {
    val colors = listOf(Palette.accent, Palette.metricRose, Palette.metricAmber, Palette.metricPurple)
    val selectedKeys = selected.filter { it in keys }.distinct().take(4)
    val traces = selectedKeys.mapIndexed { index, key ->
        val (title, unit, note) = ouraPresentation(key, imported, temp)
        val (scale, offset) = ouraValueTransform(key, temp)
        OuraCompareTrace(OuraComparison.Trace(key, ouraComparisonDimension(key), unit,
            signals[key] ?: SignalDecimation.Summary(), scale, offset), title, note, colors[index])
    }
    val axes = remember(traces) { OuraComparison.axes(traces.map { it.trace }) }
    val cursor = rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(from, to) { if (cursor.value?.let { it < from || it >= to } == true) cursor.value = null }
    val context = LocalContext.current
    val formatter = rememberOuraTimestampFormatter(seconds = true)
    fun time(ts: Long) = formatter.format(Instant.ofEpochSecond(ts))
    fun value(trace: OuraComparison.Trace, number: Double) = String.format(Locale.getDefault(), "%.1f %s", trace.display(number), trace.unit)
    val descriptions = traces.map { ui ->
        val bucket = cursor.value?.let { OuraComparison.bucketAt(ui.trace.summary.buckets, it) }
        if (bucket == null) ui.label + ": —"
        else ui.label + ": " + context.getString(R.string.oura_bucket_detail, value(ui.trace, bucket.median),
            value(ui.trace, bucket.minimum), value(ui.trace, bucket.maximum), bucket.count) + " · " + time(bucket.start) + " – " + time(bucket.end)
    }
    OuraPanel {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
            Text(stringResource(R.string.oura_compare_title), style = NoopType.headline, color = Palette.textPrimary)
            Text(stringResource(R.string.oura_compare_info), style = NoopType.caption, color = Palette.textSecondary)
            Text(stringResource(R.string.oura_compare_count, selectedKeys.size), style = NoopType.captionNumber, color = Palette.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Metrics.space8), verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                keys.forEach { key ->
                    val (title) = ouraPresentation(key, imported, temp)
                    FilterChip(shape = androidx.compose.foundation.shape.RoundedCornerShape(Metrics.cornerXs), selected = key in selectedKeys,
                        onClick = { onSelect(OuraComparison.toggle(selectedKeys, key)) },
                        enabled = key in selectedKeys || selectedKeys.size < 4,
                        modifier = Modifier.testTag("oura-trace-$key"),
                        label = { Text(title, style = NoopType.caption) },
                        colors = FilterChipDefaults.filterChipColors(containerColor = Palette.surfaceInset,
                            selectedContainerColor = Palette.accent.copy(alpha = StrandAlpha.selectedFill),
                            labelColor = Palette.textSecondary, selectedLabelColor = Palette.textPrimary))
                }
            }
            if (traces.isEmpty()) Text(stringResource(R.string.oura_compare_choose), style = NoopType.body, color = Palette.textSecondary)
            else {
                OuraChartLegend(traces.mapIndexed { index, ui ->
                    OuraLegendEntry(ui.trace.id, ui.label, ui.trace.unit, ui.color, ouraComparisonTraceStyle(index))
                })
                // Bounds retain the corresponding signal names when quantities use separate scales.
                axes.forEach { (id, axis) ->
                    val members = traces.filter { it.trace.axisId == id }
                    Text(members.joinToString(" / ") { it.label } + ": " + stringResource(R.string.oura_compare_bounds,
                        String.format(Locale.getDefault(), "%.1f", axis.minimum),
                        String.format(Locale.getDefault(), "%.1f", axis.maximum), members.first().trace.unit),
                        style = NoopType.captionNumber, color = Palette.textSecondary)
                }
                OuraComparisonPlot(traces, axes, from, to, cursor, descriptions.joinToString("\n"),
                    Modifier.fillMaxWidth().height(Metrics.largeChartHeight).testTag("oura-comparison-plot"))
                Row(Modifier.fillMaxWidth()) {
                    Text(time(from), Modifier.weight(1f), style = NoopType.captionNumber, color = Palette.textSecondary)
                    Text(time(to), Modifier.weight(1f), style = NoopType.captionNumber, color = Palette.textSecondary, textAlign = TextAlign.End)
                }
                Text(stringResource(R.string.oura_inspect), style = NoopType.caption, color = Palette.textTertiary)
                traces.forEach { ui ->
                    val bucket = cursor.value?.let { OuraComparison.bucketAt(ui.trace.summary.buckets, it) }
                    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                        Text(ui.label, style = NoopType.subhead, color = ui.color)
                        if (cursor.value == null) {
                            Text(stringResource(R.string.oura_reading_counts, ui.trace.summary.count, ui.trace.summary.buckets.size),
                                style = NoopType.caption, color = Palette.textSecondary)
                        } else if (bucket == null) Text("—", style = NoopType.bodyNumber, color = Palette.textTertiary)
                        else {
                            Text(stringResource(R.string.oura_bucket_detail, value(ui.trace, bucket.median), value(ui.trace, bucket.minimum),
                                value(ui.trace, bucket.maximum), bucket.count), style = NoopType.captionNumber, color = Palette.textPrimary)
                            Text(time(bucket.start) + " – " + time(bucket.end), style = NoopType.captionNumber, color = Palette.textSecondary)
                        }
                        if (ui.note.isNotBlank()) Text(ui.note, style = NoopType.caption, color = Palette.textSecondary)
                    }
                }
            }
        }
    }
}

/** Identity includes semantic dimension as well as unit: a temperature deviation is not absolute temperature. */
internal fun ouraComparisonDimension(key: String): String = when (key) {
    "hr", "rhr" -> "heart_rate"
    "temperature" -> "absolute_temperature"
    "skin_temp_dev_c" -> "relative_temperature"
    "motion_x", "motion_y", "motion_z" -> "motion_axes"
    "live_motion_x", "live_motion_y", "live_motion_z" -> "host_signed16_axes"
    "motion_low", "motion_high" -> "motion_intensity"
    "energy_kcal", "total_kcal" -> "energy"
    "sleep_total_min", "sleep_deep_min", "sleep_rem_min" -> "sleep_duration"
    else -> key
}

@Composable
private fun OuraComparisonPlot(traces: List<OuraCompareTrace>, axes: Map<String, OuraComparison.Axis>,
    from: Long, to: Long, cursor: MutableState<Long?>, description: String, modifier: Modifier) {
    val anchors = remember(traces) { traces.flatMap { it.trace.summary.buckets.map { bucket -> bucket.start + (bucket.end - bucket.start) / 2 } }.distinct().sorted() }
    val previous = stringResource(R.string.oura_previous_reading)
    val next = stringResource(R.string.oura_next_reading)
    val title = stringResource(R.string.oura_compare_title)
    Spacer(modifier.semantics {
        contentDescription = title
        stateDescription = description
        customActions = listOf(
            CustomAccessibilityAction(previous) {
                val target = if (cursor.value == null) anchors.lastOrNull() else anchors.lastOrNull { it < cursor.value!! }
                if (target == null) false else { cursor.value = target; true }
            },
            CustomAccessibilityAction(next) {
                val target = if (cursor.value == null) anchors.firstOrNull() else anchors.firstOrNull { it > cursor.value!! }
                if (target == null) false else { cursor.value = target; true }
            })
    }.pointerInput(from, to) {
        detectTapGestures { cursor.value = from + ((it.x / size.width.coerceAtLeast(1)) * (to - from)).toLong().coerceIn(0, (to - from - 1).coerceAtLeast(0)) }
    }.pointerInput(from, to) {
        fun inspect(x: Float) { cursor.value = from + ((x / size.width.coerceAtLeast(1)) * (to - from)).toLong().coerceIn(0, (to - from - 1).coerceAtLeast(0)) }
        detectHorizontalDragGestures(onDragStart = { inspect(it.x) }) { change, _ -> change.consume(); inspect(change.position.x) }
    }.drawWithCache {
        fun x(ts: Long) = ((ts - from).toDouble() / (to - from).coerceAtLeast(1) * size.width).toFloat()
        fun point(ui: OuraCompareTrace, ts: Long, number: Double): Offset {
            val axis = axes.getValue(ui.trace.axisId)
            return Offset(x(ts), (size.height * (1 - (ui.trace.display(number) - axis.minimum) / (axis.maximum - axis.minimum))).toFloat())
        }
        val paths = traces.mapIndexed { index, ui ->
            val path = Path()
            val buckets = ui.trace.summary.buckets
            if (ui.trace.axisId in axes) buckets.forEachIndexed { i, bucket ->
                val p = point(ui, bucket.start + (bucket.end - bucket.start) / 2, bucket.median)
                if (i == 0 || buckets[i - 1].end != bucket.start) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            path to ouraTraceStroke(ouraComparisonTraceStyle(index))
        }
        onDrawBehind {
            clipRect {
                listOf(0f, 0.5f, 1f).forEach { f -> drawLine(Palette.hairline, Offset(0f, size.height * f), Offset(size.width, size.height * f)) }
                traces.forEachIndexed { index, ui ->
                    if (ui.trace.axisId in axes) {
                        drawPath(paths[index].first, ui.color, style = paths[index].second)
                        val buckets = ui.trace.summary.buckets
                        buckets.forEachIndexed { i, bucket ->
                            if ((i == 0 || buckets[i - 1].end != bucket.start) && (i == buckets.lastIndex || bucket.end != buckets[i + 1].start))
                                drawCircle(ui.color, Metrics.space2.toPx(), point(ui, bucket.start + (bucket.end - bucket.start) / 2, bucket.median))
                        }
                        cursor.value?.let { ts -> OuraComparison.bucketAt(buckets, ts)?.let { bucket ->
                            drawCircle(ui.color, Metrics.space4.toPx(), point(ui, ts, bucket.median))
                        } }
                    }
                }
                cursor.value?.let { drawLine(Palette.textSecondary, Offset(x(it), 0f), Offset(x(it), size.height)) }
            }
        }
    })
}
