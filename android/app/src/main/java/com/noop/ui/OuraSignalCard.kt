package com.noop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.noop.R
import com.noop.analytics.SignalDecimation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Median trace, optional range bands, and inspection of the actual bucket (never across a gap). */
@Composable
internal fun OuraSignalCard(
    title: String, unit: String, note: String, summary: SignalDecimation.Summary,
    from: Long, to: Long, showRange: Boolean, valueScale: Double = 1.0, valueOffset: Double = 0.0,
    modifier: Modifier = Modifier, detailsContent: @Composable () -> Unit = {},
) {
    var smoothing by rememberSaveable(title) { mutableIntStateOf(0) }
    var details by rememberSaveable(title, from, to) { mutableStateOf(false) }
    val selected = remember(summary, from, to) { mutableIntStateOf(-1) }
    val buckets = summary.buckets
    val trend = remember(buckets, smoothing) { ouraHistoryTrend(buckets, smoothing) }
    val locale = Locale.getDefault()
    val format = remember(locale, valueScale, valueOffset, unit) {
        { value: Double -> String.format(locale, "%.1f %s", value * valueScale + valueOffset, unit) }
    }
    // Keep the same domain when revealing extrema, so toggling the band does not move the trace.
    val domain = remember(summary, valueScale, valueOffset) {
        val low = (summary.minimum ?: 0.0) * valueScale + valueOffset
        val high = (summary.maximum ?: 1.0) * valueScale + valueOffset
        val pad = maxOf((high - low) * 0.08, 0.5)
        (low - pad) to (high + pad)
    }
    val is24h = ClockPrefs.uses24Hour(LocalContext.current)
    val zone = ZoneId.systemDefault()
    val timeFormat = remember(locale, zone, is24h) {
        DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", locale).withZone(zone)
    }
    val dateFormat = remember(locale, zone) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).withZone(zone)
    }
    val axisFormat = if (to - from <= 25 * 3600L) timeFormat else dateFormat
    val description = stringResource(R.string.oura_reading_counts, summary.count, buckets.size)
    val context = LocalContext.current
    val accessibleBuckets = remember(buckets, format, dateFormat, timeFormat, context) {
        buckets.map { bucket ->
            dateFormat.format(Instant.ofEpochSecond(bucket.start)) + " · " +
                timeFormat.format(Instant.ofEpochSecond(bucket.start)) + " – " +
                dateFormat.format(Instant.ofEpochSecond(bucket.end)) + " · " +
                timeFormat.format(Instant.ofEpochSecond(bucket.end)) + ". " +
                context.getString(R.string.oura_bucket_detail, format(bucket.median), format(bucket.minimum), format(bucket.maximum), bucket.count)
        }
    }

    if (details) OuraDetailsDialog(title, { details = false }) {
        OuraSummaryDetails(summary, unit, note, valueScale, valueOffset, smoothing)
        OuraBucketReadout(buckets, selected, format, timeFormat, dateFormat)
        detailsContent()
    }
    OuraPanel(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
            Text(title, style = NoopType.subhead, color = Palette.textPrimary,
                modifier = Modifier.clickable(onClickLabel = stringResource(R.string.oura_details_open)) { details = true })
            if (summary.count == 0) {
                Text(stringResource(R.string.oura_no_readings), style = NoopType.body, color = Palette.textSecondary)
            } else {
                OuraHistorySmoothing(smoothing) { smoothing = it }
                OuraChartLegend(buildList {
                    add(OuraLegendEntry("median", stringResource(if (smoothing == 0) R.string.oura_legend_median else R.string.oura_live_smoothed), unit, Palette.accent, OuraTraceStyle.Solid))
                    if (showRange) add(OuraLegendEntry("range", stringResource(R.string.oura_legend_range), unit, Palette.accent, OuraTraceStyle.Range))
                })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                    Column(Modifier.width(Metrics.chartAxisLabelWidth).height(Metrics.chartHeight), verticalArrangement = Arrangement.SpaceBetween) {
                        Text(String.format(locale, "%.1f", domain.second), style = NoopType.captionNumber, color = Palette.textSecondary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(String.format(locale, "%.1f", domain.first), style = NoopType.captionNumber, color = Palette.textSecondary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OuraSignalPlot(buckets, trend, from, to, domain.first, domain.second, valueScale, valueOffset,
                        showRange, selected, "$title: $description", accessibleBuckets, Modifier.weight(1f).height(Metrics.chartHeight),
                        onInspect = { details = true })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                    Text(axisFormat.format(Instant.ofEpochSecond(from)), Modifier.weight(1f), style = NoopType.caption, color = Palette.textSecondary)
                    Text(axisFormat.format(Instant.ofEpochSecond(to)), Modifier.weight(1f), style = NoopType.caption,
                        color = Palette.textSecondary, textAlign = TextAlign.End)
                }

            }
        }
    }
}

/** Selection is read in drawing/readout only; finger movement does not rebuild paths or axis labels. */
@Composable
private fun OuraSignalPlot(
    buckets: List<SignalDecimation.Bucket>, trend: List<Double>, from: Long, to: Long, yMin: Double, yMax: Double,
    valueScale: Double, valueOffset: Double, showRange: Boolean, selected: MutableIntState,
    description: String, accessibleBuckets: List<String>, modifier: Modifier, onInspect: () -> Unit,
) {
    val reduced = rememberReduceMotion()
    val bandAlpha = animateFloatAsState(if (showRange) StrandAlpha.selectedFill else 0f,
        animationSpec = if (reduced) tween(0) else NoopMotion.value())
    val openLabel = stringResource(R.string.oura_details_open)
    val inspectHint = stringResource(R.string.oura_inspect)
    val previous = stringResource(R.string.oura_previous_reading)
    val next = stringResource(R.string.oura_next_reading)
    Spacer(modifier.testTag("oura-history-plot")
        .semantics {
            onClick(label = openLabel) { onInspect(); true }
            contentDescription = description
            stateDescription = accessibleBuckets.getOrNull(selected.intValue) ?: inspectHint
            customActions = listOf(
                CustomAccessibilityAction(previous) {
                    if (buckets.isEmpty() || selected.intValue == 0) false
                    else { selected.intValue = if (selected.intValue < 0) buckets.lastIndex else selected.intValue - 1; true }
                },
                CustomAccessibilityAction(next) {
                    if (selected.intValue >= buckets.lastIndex) false
                    else { selected.intValue++; true }
                },
            )
        }
        .pointerInput(buckets, from, to, selected) {
            fun inspect(x: Float) {
                val ts = from + (x / size.width.coerceAtLeast(1) * (to - from)).toLong()
                selected.intValue = com.noop.analytics.OuraComparison.indexAt(buckets, ts)
            }
            detectTapGestures { inspect(it.x); onInspect() }
        }
        .pointerInput(buckets, from, to, selected) {
            fun inspect(x: Float) {
                val ts = from + (x / size.width.coerceAtLeast(1) * (to - from)).toLong()
                selected.intValue = com.noop.analytics.OuraComparison.indexAt(buckets, ts)
            }
            detectHorizontalDragGestures(onDragStart = { inspect(it.x) }, onDragEnd = onInspect) { change, _ ->
                change.consume()
                inspect(change.position.x)
            }
        }
        .drawWithCache {
            fun x(ts: Long) = ((ts - from).toDouble() / (to - from).coerceAtLeast(1) * size.width).toFloat()
            fun y(value: Double) = (size.height * (1 - (value * valueScale + valueOffset - yMin) / (yMax - yMin))).toFloat()
            val points = buckets.mapIndexed { index, bucket -> Offset(x(bucket.start + (bucket.end - bucket.start) / 2), y(trend[index])) }
            val trace = Path().apply {
                buckets.forEachIndexed { index, bucket ->
                    val point = points[index]
                    if (index == 0 || buckets[index - 1].end != bucket.start) moveTo(point.x, point.y)
                    else lineTo(point.x, point.y)
                }
            }
            val ranges = Path().apply {
                buckets.forEach { addRect(Rect(x(it.start), y(it.maximum), x(it.end), y(it.minimum))) }
            }
            val stroke = ouraTraceStroke(OuraTraceStyle.Solid)
            val radius = Metrics.space2.toPx()
            onDrawBehind {
                for (fraction in listOf(0f, 0.5f, 1f)) {
                    drawLine(Palette.hairline, Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction))
                }
                if (bandAlpha.value > 0f) drawPath(ranges, Palette.accent.copy(alpha = bandAlpha.value))
                drawPath(trace, Palette.accent, style = stroke)
                points.forEachIndexed { index, point ->
                    // Dense traces stay clean; isolated observations remain visible.
                    if (buckets.size < 12 ||
                        ((index == 0 || buckets[index - 1].end != buckets[index].start) &&
                            (index == buckets.lastIndex || buckets[index].end != buckets[index + 1].start))) {
                        drawCircle(Palette.accent, radius = radius, center = point)
                    }
                }
                points.getOrNull(selected.intValue)?.let { point ->
                    drawLine(Palette.textSecondary, Offset(point.x, 0f), Offset(point.x, size.height))
                    drawCircle(Palette.accent.copy(alpha = StrandAlpha.selectedFill), Metrics.space8.toPx(), point)
                    drawCircle(Palette.accent, Metrics.space4.toPx(), point)
                }
            }
        })
}

@Composable
private fun OuraBucketReadout(
    buckets: List<SignalDecimation.Bucket>, selected: MutableIntState, format: (Double) -> String,
    timeFormat: DateTimeFormatter, dateFormat: DateTimeFormatter,
) {
    val bucket = buckets.getOrNull(selected.intValue)
    val time = bucket?.let {
        val startDate = dateFormat.format(Instant.ofEpochSecond(it.start))
        val endDate = dateFormat.format(Instant.ofEpochSecond(it.end))
        startDate + " · " + timeFormat.format(Instant.ofEpochSecond(it.start)) + " – " +
            (if (endDate != startDate) "$endDate · " else "") + timeFormat.format(Instant.ofEpochSecond(it.end))
    } ?: stringResource(R.string.oura_inspect)
    val detail = bucket?.let {
        stringResource(R.string.oura_bucket_detail, format(it.median), format(it.minimum), format(it.maximum), it.count)
    } ?: ""
    // Let exact values wrap naturally, without reserving an empty detail panel.
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
        Text(time, style = NoopType.captionNumber, color = Palette.textPrimary)
        if (detail.isNotEmpty()) Text(detail, style = NoopType.captionNumber, color = Palette.textSecondary)
    }
}
