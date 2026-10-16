package com.noop.ui

import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.text.style.TextAlign
import com.noop.R
import java.util.Locale
import kotlin.math.abs

/** A held absolute interval prevents incoming Live receipts from moving a zoomed inspection. */
@Stable
internal class OuraChartViewport(private val domainState: State<OuraChartWindow>, private val followState: State<OuraChartWindow>) {
    private var held by mutableStateOf<OuraChartWindow?>(null)
    val domain: OuraChartWindow get() = domainState.value
    val fullWindow: OuraChartWindow get() = followState.value
    val window: OuraChartWindow get() = held?.let { ouraChartClamp(it, domain) } ?: fullWindow
    val isHeld: Boolean get() = held != null
    fun transform(zoom: Float, anchor: Float, pan: Float) {
        val transformDomain = if (held == null) OuraChartWindow(minOf(domain.from, fullWindow.from), domain.to) else domain
        val result = ouraChartTransform(window, transformDomain, zoom, anchor, pan, fullWindow.span)
        held = if (result == fullWindow) null else ouraChartClamp(result, domain)
    }
    fun reset() { held = null }
    fun at(fraction: Float): Long = (window.from + (window.span * fraction.coerceIn(0f, 1f)).toLong()).coerceAtMost(window.to - 1)
}

@Composable
internal fun rememberOuraChartViewport(from: Long, to: Long, key: Any?, availableFrom: Long = from): OuraChartViewport {
    val domain = rememberUpdatedState(OuraChartWindow(availableFrom, maxOf(from + 1, to)))
    val follow = rememberUpdatedState(OuraChartWindow(from, maxOf(from + 1, to)))
    return remember(key) { OuraChartViewport(domain, follow) }
}

/** One owner arbitrates inspection, transforms and vertical page scrolling. */
internal fun Modifier.ouraChartGestures(viewport: OuraChartViewport, onInspect: (Long) -> Unit): Modifier = composed {
    val inspect by rememberUpdatedState(onInspect)
    pointerInput(viewport) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var multiple = false
            var dragging = false
            do {
                val event = awaitPointerEvent()
                if (event.changes.any { it.isConsumed }) break
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) {
                    multiple = true
                    val center = event.calculateCentroid(useCurrent = true)
                    val width = size.width.coerceAtLeast(1).toFloat()
                    viewport.transform(event.calculateZoom(), center.x / width, event.calculatePan().x / width)
                    event.changes.forEach { it.consume() }
                } else if (!multiple) {
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val delta = change.position - down.position
                    if (!dragging && abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) > abs(delta.x)) break
                    if (abs(delta.x) > viewConfiguration.touchSlop) dragging = true
                    if (dragging || !change.pressed) {
                        inspect(viewport.at(change.position.x / size.width.coerceAtLeast(1)))
                        change.consume()
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}

@Composable
internal fun ouraChartZoomActions(viewport: OuraChartViewport): List<CustomAccessibilityAction> = listOf(
    CustomAccessibilityAction(stringResource(R.string.oura_chart_zoom_in)) { viewport.transform(2f, .5f, 0f); true },
    CustomAccessibilityAction(stringResource(R.string.oura_chart_zoom_out)) { viewport.transform(.5f, .5f, 0f); true },
    CustomAccessibilityAction(stringResource(R.string.oura_chart_reset)) { viewport.reset(); true },
)

internal data class OuraChartScale(val label: String, val unit: String, val minimum: Double,
    val maximum: Double, val color: Color)

internal fun ouraChartSeconds(seconds: Double, template: String): String = String.format(Locale.getDefault(), template, String.format(Locale.getDefault(), "%.2f", seconds))

private fun chartAxisNumber(number: Double): String = String.format(Locale.getDefault(), if (abs(number) >= 1000) "%.0f" else "%.1f", number)

/** Unit scales align to the grid; extra comparison quantities retain explicitly named tick values. */
@Composable
internal fun OuraChartFrame(tag: String, scales: List<OuraChartScale>, viewport: OuraChartViewport,
    xTitle: String, formatTime: (Long) -> String, large: Boolean = false, plot: @Composable (Modifier) -> Unit) {
    val height = if (large) Metrics.largeChartHeight else Metrics.chartHeight
    val window = viewport.window
    val primary = scales.firstOrNull()
    val secondary = scales.getOrNull(1)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        primary?.let { Text(it.label + " · " + it.unit, Modifier.weight(1f), style = NoopType.caption, color = it.color) }
        secondary?.let { Text(it.label + " · " + it.unit, Modifier.weight(1f), style = NoopType.caption, color = it.color, textAlign = TextAlign.End) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        primary?.let { OuraChartYTicks(it, Modifier.height(height).testTag("$tag-y-axis")) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
            plot(Modifier.fillMaxWidth().height(height))
            Row(Modifier.fillMaxWidth().testTag("$tag-x-axis")) {
                listOf(window.from, window.from + window.span / 2, window.to).forEachIndexed { index, time ->
                    Text(formatTime(time), Modifier.weight(1f), style = NoopType.captionNumber, color = Palette.textSecondary,
                        textAlign = when (index) { 0 -> TextAlign.Start; 1 -> TextAlign.Center; else -> TextAlign.End })
                }
            }
        }
        secondary?.let { OuraChartYTicks(it, Modifier.height(height), TextAlign.End) }
    }
    // Up to four incompatible quantities cannot share numerical labels on a narrow phone.
    // The first two have edge rails; remaining scales list the same lower/middle/upper grid ticks.
    scales.drop(2).forEach { scale ->
        Text(stringResource(R.string.oura_chart_extra_scale, scale.label, chartAxisNumber(scale.minimum),
            chartAxisNumber((scale.minimum + scale.maximum) / 2), chartAxisNumber(scale.maximum), scale.unit),
            style = NoopType.captionNumber, color = scale.color)
    }
    Text(xTitle, style = NoopType.caption, color = Palette.textSecondary)
    if (viewport.isHeld) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.oura_chart_zoom_amount, String.format(Locale.getDefault(), "%.1f", viewport.fullWindow.span.toDouble() / window.span)),
                style = NoopType.captionNumber, color = Palette.textSecondary)
            TextButton(onClick = viewport::reset, modifier = Modifier.testTag("$tag-reset")) {
                Text(stringResource(R.string.oura_chart_reset), style = NoopType.caption)
            }
        }
    }
    Text(stringResource(R.string.oura_chart_gestures), style = NoopType.caption, color = Palette.textTertiary)
}

@Composable
private fun OuraChartYTicks(scale: OuraChartScale, modifier: Modifier, align: TextAlign = TextAlign.Start) {
    Column(modifier.widthIn(min = Metrics.chartAxisLabelWidth), verticalArrangement = Arrangement.SpaceBetween) {
        for (value in listOf(scale.maximum, (scale.minimum + scale.maximum) / 2, scale.minimum)) {
            Text(chartAxisNumber(value), style = NoopType.captionNumber, color = scale.color, textAlign = align)
        }
    }
}
