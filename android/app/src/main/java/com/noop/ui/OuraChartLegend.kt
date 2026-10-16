package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Density
import com.noop.R

internal enum class OuraTraceStyle { Solid, Dashed, Dotted, DashDot, Range }
internal data class OuraLegendEntry(val key: String, val label: String, val unit: String,
    val color: Color, val style: OuraTraceStyle, val value: String? = null)

internal fun ouraLiveTraceStyle(key: String): OuraTraceStyle = when (key) {
    "hr" -> OuraTraceStyle.DashDot
    "y" -> OuraTraceStyle.Dashed
    "z" -> OuraTraceStyle.Dotted
    else -> OuraTraceStyle.Solid
}
internal fun ouraComparisonTraceStyle(index: Int): OuraTraceStyle =
    listOf(OuraTraceStyle.Solid, OuraTraceStyle.Dashed, OuraTraceStyle.Dotted, OuraTraceStyle.DashDot)[index % 4]

/** Both chart and legend resolve the same token-based line pattern. */
internal fun Density.ouraTraceStroke(style: OuraTraceStyle): Stroke {
    val effect = when (style) {
        OuraTraceStyle.Dashed -> PathEffect.dashPathEffect(floatArrayOf(Metrics.space8.toPx(), Metrics.space4.toPx()))
        OuraTraceStyle.Dotted -> PathEffect.dashPathEffect(floatArrayOf(Metrics.space2.toPx(), Metrics.space4.toPx()))
        OuraTraceStyle.DashDot -> PathEffect.dashPathEffect(floatArrayOf(Metrics.space8.toPx(), Metrics.space4.toPx(), Metrics.space2.toPx(), Metrics.space4.toPx()))
        else -> null
    }
    return Stroke(Metrics.space2.toPx(), cap = StrokeCap.Round, pathEffect = effect)
}

/** A visible key stays adjacent to its plot and wraps at large text sizes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OuraChartLegend(entries: List<OuraLegendEntry>) {
    if (entries.isEmpty()) return
    Column(Modifier.testTag("oura-chart-legend"), verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
        Text(stringResource(R.string.oura_legend), style = NoopType.caption, color = Palette.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Metrics.space12), verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
            entries.forEach { entry ->
                val style = stringResource(when (entry.style) {
                    OuraTraceStyle.Solid -> R.string.oura_legend_solid
                    OuraTraceStyle.Dashed -> R.string.oura_legend_dashed
                    OuraTraceStyle.Dotted -> R.string.oura_legend_dotted
                    OuraTraceStyle.DashDot -> R.string.oura_legend_dash_dot
                    OuraTraceStyle.Range -> R.string.oura_legend_range
                })
                Row(Modifier.testTag("oura-legend-${entry.key}").semantics(mergeDescendants = true) {
                    stateDescription = style
                }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                    Canvas(Modifier.width(Metrics.space24).height(Metrics.space8)) {
                        if (entry.style == OuraTraceStyle.Range) drawRect(entry.color.copy(alpha = StrandAlpha.selectedFill))
                        else {
                            val stroke = ouraTraceStroke(entry.style)
                            drawLine(entry.color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                                stroke.width, cap = stroke.cap, pathEffect = stroke.pathEffect)
                        }
                    }
                    Text(listOfNotNull(entry.label, entry.value, entry.unit.takeIf { it.isNotBlank() }).joinToString(" · "),
                        style = NoopType.captionNumber, color = Palette.textPrimary)
                }
            }
        }
    }
}
