package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    from: Long, to: Long, showRange: Boolean, transform: (Double) -> Double = { it },
) {
    var selected by remember(summary, from, to) { mutableStateOf<SignalDecimation.Bucket?>(null) }
    val buckets = summary.buckets
    fun format(value: Double) = String.format(Locale.getDefault(), "%.1f %s", transform(value), unit)
    val axisValues = buckets.flatMap { if (showRange) listOf(it.minimum, it.maximum) else listOf(it.median) }.map(transform)
    val low = axisValues.minOrNull() ?: 0.0
    val high = axisValues.maxOrNull() ?: 1.0
    val pad = maxOf((high - low) * 0.08, 0.5)
    val yMin = low - pad
    val yMax = high + pad
    val timeFormat = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()) }
    val description = stringResource(R.string.oura_reading_counts, summary.count, buckets.size)

    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Text(title, style = NoopType.headline, color = Palette.textPrimary)
            if (note.isNotEmpty()) Text(note, style = NoopType.caption, color = Palette.textSecondary)
            if (summary.count == 0) {
                Text(stringResource(R.string.oura_no_readings), style = NoopType.body, color = Palette.textSecondary)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                    Column(Modifier.height(Metrics.chartHeight), verticalArrangement = Arrangement.SpaceBetween) {
                        Text(String.format(Locale.getDefault(), "%.1f", yMax), style = NoopType.captionNumber, color = Palette.textSecondary)
                        Text(String.format(Locale.getDefault(), "%.1f", yMin), style = NoopType.captionNumber, color = Palette.textSecondary)
                    }
                    Canvas(Modifier.weight(1f).height(Metrics.chartHeight)
                        .semantics { contentDescription = "$title: $description" }
                        .pointerInput(buckets, from, to) {
                            detectTapGestures { position ->
                                val ts = from + ((position.x / size.width.coerceAtLeast(1)) * (to - from)).toLong()
                                selected = buckets.firstOrNull { ts >= it.start && ts < it.end }
                            }
                        }
                        .pointerInput(buckets, from, to) {
                            detectHorizontalDragGestures { change, _ ->
                                val ts = from + ((change.position.x / size.width.coerceAtLeast(1)) * (to - from)).toLong()
                                selected = buckets.firstOrNull { ts >= it.start && ts < it.end }
                            }
                        }) {
                        fun x(ts: Long) = ((ts - from).toDouble() / (to - from).coerceAtLeast(1) * size.width).toFloat()
                        fun y(value: Double) = (size.height * (1 - (transform(value) - yMin) / (yMax - yMin))).toFloat()
                        for (fraction in listOf(0f, 0.5f, 1f)) {
                            drawLine(Palette.hairline, Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction))
                        }
                        buckets.forEachIndexed { index, b ->
                            val center = b.start + (b.end - b.start) / 2
                            if (showRange) drawRect(Palette.accent.copy(alpha = 0.15f),
                                topLeft = Offset(x(b.start), y(b.maximum)),
                                size = Size((x(b.end) - x(b.start)).coerceAtLeast(0f), (y(b.minimum) - y(b.maximum)).coerceAtLeast(0f)))
                            if (index > 0) {
                                val previous = buckets[index - 1]
                                if (previous.end == b.start) drawLine(Palette.accent,
                                    Offset(x(previous.start + (previous.end - previous.start) / 2), y(previous.median)),
                                    Offset(x(center), y(b.median)), strokeWidth = Metrics.space2.toPx())
                            }
                            drawCircle(Palette.accent, radius = Metrics.space2.toPx(), center = Offset(x(center), y(b.median)))
                        }
                        selected?.let { b ->
                            val at = x(b.start + (b.end - b.start) / 2)
                            drawLine(Palette.textSecondary, Offset(at, 0f), Offset(at, size.height))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(timeFormat.format(Instant.ofEpochSecond(from)), style = NoopType.caption, color = Palette.textSecondary)
                    Text(timeFormat.format(Instant.ofEpochSecond(to)), style = NoopType.caption, color = Palette.textSecondary)
                }
                selected?.let { b ->
                    Text(timeFormat.format(Instant.ofEpochSecond(b.start)) + " – " + timeFormat.format(Instant.ofEpochSecond(b.end)),
                        style = NoopType.captionNumber, color = Palette.textPrimary)
                    Text(stringResource(R.string.oura_bucket_detail, format(b.median), format(b.minimum), format(b.maximum), b.count),
                        style = NoopType.captionNumber, color = Palette.textPrimary)
                } ?: Text(stringResource(R.string.oura_inspect), style = NoopType.caption, color = Palette.textSecondary)
                summary.mean?.let { mean ->
                    Text(stringResource(R.string.oura_summary, format(mean), format(summary.minimum!!), format(summary.maximum!!)),
                        style = NoopType.captionNumber, color = Palette.textSecondary)
                }
                Text(description, style = NoopType.caption, color = Palette.textSecondary)
            }
        }
    }
}
