package com.noop.ui

import kotlin.math.roundToLong

/** Display time bounds only; chart gestures never change acquisition or stored values. */
internal data class OuraChartWindow(val from: Long, val to: Long) {
    val span: Long get() = (to - from).coerceAtLeast(1)
}

internal fun ouraChartClamp(window: OuraChartWindow, domain: OuraChartWindow): OuraChartWindow {
    val span = window.span.coerceAtMost(domain.span)
    val start = window.from.coerceIn(domain.from, (domain.to - span).coerceAtLeast(domain.from))
    return OuraChartWindow(start, start + span)
}

internal fun ouraChartTransform(window: OuraChartWindow, domain: OuraChartWindow, zoom: Float,
    anchor: Float, pan: Float, zoomBaseSpan: Long = domain.span): OuraChartWindow {
    val current = ouraChartClamp(window, domain)
    if (!zoom.isFinite() || zoom <= 0 || !anchor.isFinite() || !pan.isFinite()) return current
    val minimumSpan = (1 + (zoomBaseSpan.coerceAtLeast(1) - 1) / 64).coerceAtMost(domain.span)
    val span = (current.span / zoom.toDouble()).roundToLong().coerceIn(minimumSpan, domain.span)
    val focus = anchor.coerceIn(0f, 1f).toDouble()
    val start = (current.from + (current.span - span) * focus - pan.toDouble() * current.span).roundToLong()
    return ouraChartClamp(OuraChartWindow(start, start + span), domain)
}
