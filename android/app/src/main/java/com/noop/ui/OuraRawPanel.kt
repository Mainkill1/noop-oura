package com.noop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import com.noop.R
import com.noop.ble.*
import com.noop.oura.*
import java.util.Locale

internal enum class OuraRawDisplay { Graph, Gauge, Numbers, Bytes }

@Composable
internal fun OuraRawPanel(state: OuraRawState, now: Long, channelKey: String, intervalMs: Long,
    eligible: Boolean, connected: Boolean, onChannel: (String) -> Unit, onInterval: (Long) -> Unit, detailsContent: @Composable () -> Unit = {}) {
    var fieldKey by rememberSaveable(channelKey) { mutableStateOf("") }
    var display by rememberSaveable { mutableStateOf(OuraRawDisplay.Graph) }
    var smooth by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    val unknown = (state.capture.records.map { it.observation.channel } + channelKey).distinct().filter { key -> OuraRawInstruments.catalog.none { it.key == key } }
        .map { OuraRawChannel(it, "0x" + it.substringAfter(':').uppercase(), "", "record", false) }
    val catalog = OuraRawInstruments.catalog + unknown
    val channel = catalog.firstOrNull { it.key == channelKey } ?: catalog.first()
    val receipts = state.capture.records.filter { it.observation.channel == channelKey }
    val latest = receipts.lastOrNull()
    val fields = receipts.flatMap { it.observation.fields }.distinctBy { it.key }
    val choices = (if (fields.any { it.key == "x" } && fields.any { it.key == "y" } && fields.any { it.key == "z" }) listOf("xyz") else emptyList()) + fields.map { it.key }
    val selected = fieldKey.takeIf { it in choices } ?: choices.firstOrNull().orEmpty()
    val selectedFields = if (selected == "xyz") fields.filter { it.key in setOf("x", "y", "z") } else fields.filter { it.key == selected }
    // A named bounded capture window, not an invented duration of physiological samples. Multiple
    // values in one record share the actual receipt time; no per-sample timestamp is guessed.
    val values = selectedFields.associate { field -> field.key to receipts.flatMap { receipt ->
        receipt.observation.fields.firstOrNull { it.key == field.key }?.values.orEmpty().map { OuraLivePoint(receipt.receivedAtElapsedMs, it) }
    } }
    val kind = stringResource(when (channel.acquisition) { "push" -> R.string.oura_raw_push; "poll" -> R.string.oura_raw_poll; else -> R.string.oura_raw_record })
    OuraPanel {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
            Text(stringResource(R.string.oura_raw_title), style = NoopType.headline, color = Palette.textPrimary,
                modifier = Modifier.clickable(onClickLabel = stringResource(R.string.oura_details_open)) { details = true })
            OuraRawDropdown(stringResource(R.string.oura_raw_channel), channelKey, catalog.map { it.key },
                { key -> val item = catalog.first { it.key == key }; item.number + " · " + rawChannelLabel(item) }, onChannel, "oura-raw-channel")
            if (!eligible || !connected) Text(stringResource(if (!eligible) R.string.oura_live_requires_ring else R.string.oura_live_disconnected), style = NoopType.caption, color = Palette.textSecondary)
            if (!channel.supported) Text(stringResource(R.string.oura_raw_excluded), style = NoopType.body, color = Palette.textSecondary)
            else if (latest == null) Text(stringResource(if (channel.acquisition == "record") R.string.oura_raw_awaiting_record else R.string.oura_raw_never_received),
                style = NoopType.body, color = Palette.textSecondary, modifier = Modifier.testTag("oura-raw-empty"))
            if (channel.acquisition == "poll") {
                OuraRawDropdown(stringResource(R.string.oura_raw_interval), intervalMs, OuraRawPoll.INTERVALS,
                    { "${it} ms" }, onInterval, "oura-raw-interval")
                Text(stringResource(rawPollPhaseLabel(state.poll.phase)), style = NoopType.caption, color = Palette.textSecondary, modifier = Modifier.testTag("oura-raw-poll-state"))
            }
            latest?.let { receipt ->
                Text(stringResource(R.string.oura_raw_age, ((now - receipt.receivedAtElapsedMs).coerceAtLeast(0) / 1000)), style = NoopType.captionNumber,
                    color = Palette.textSecondary, modifier = Modifier.testTag("oura-raw-age"))

            }
            OuraRawDropdown(stringResource(R.string.oura_raw_field), selected, choices.ifEmpty { listOf("") },
                { key -> if (key == "xyz") stringResource(R.string.oura_raw_xyz) else if (key.isEmpty()) "—" else key + " · " + rawUnit(fields.first { it.key == key }.unit) },
                { fieldKey = it }, "oura-raw-field")
            OuraRawDropdown(stringResource(R.string.oura_raw_display), display, OuraRawDisplay.entries,
                { stringResource(when (it) { OuraRawDisplay.Graph -> R.string.oura_raw_graph; OuraRawDisplay.Gauge -> R.string.oura_raw_gauge; OuraRawDisplay.Numbers -> R.string.oura_raw_numbers; OuraRawDisplay.Bytes -> R.string.oura_raw_bytes }) },
                { display = it }, "oura-raw-display")
            when (display) {
                OuraRawDisplay.Bytes -> latest?.observation?.let { observation ->
                    Text(stringResource(R.string.oura_raw_byte_length, observation.wire.size), style = NoopType.subhead, color = Palette.textPrimary)
                    Text(stringResource(if (channel.acquisition == "record") R.string.oura_raw_record_frame else R.string.oura_raw_response_frame), style = NoopType.caption, color = Palette.textSecondary)
                    Text(observation.wire.joinToString(" ") { "%02X".format(it) }, style = NoopType.captionNumber, color = Palette.textPrimary, modifier = Modifier.testTag("oura-raw-hex"))
                    Text(observation.wire.joinToString(" "), style = NoopType.captionNumber, color = Palette.textSecondary)
                    Text(OuraRawInstruments.readable(observation.wire), style = NoopType.captionNumber, color = Palette.textSecondary)
                }
                OuraRawDisplay.Graph -> {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    OuraToolbar(listOf(false, true), smooth, { context.getString(if (it) R.string.oura_live_smoothed else R.string.oura_live_raw) }, { smooth = it })
                    OuraRawGraph(selectedFields, values, smooth, listOf(state.sourceDeviceId, state.sourceIncarnation, channelKey, selected),
                        when (channelKey) { "hr" -> 5000L; "motion" -> 1500L; else -> if (channel.acquisition == "poll") maxOf(5000L, intervalMs * 2) else 5000L })
                }
                else -> selectedFields.forEach { field ->
                    val stats = OuraRawInstruments.statistics(values[field.key].orEmpty().map { it.value })
                    if (stats != null) {
                        Text(field.key + " · " + rawUnit(field.unit), style = NoopType.subhead, color = Palette.textPrimary)
                        if (display == OuraRawDisplay.Gauge) {
                            Text(stringResource(R.string.oura_raw_observed_range, rawNumber(stats.minimum), rawNumber(stats.maximum), rawUnit(field.unit)), style = NoopType.captionNumber, color = Palette.textSecondary)
                            if (stats.maximum > stats.minimum) LinearProgressIndicator(progress = { ((stats.latest - stats.minimum) / (stats.maximum - stats.minimum)).toFloat() }, modifier = Modifier.fillMaxWidth(), color = Palette.accent)
                        }
                        Text(stringResource(R.string.oura_raw_statistics, rawNumber(stats.latest), rawNumber(stats.minimum), rawNumber(stats.maximum), rawNumber(stats.mean), stats.count),
                            style = NoopType.captionNumber, color = Palette.textPrimary, modifier = Modifier.testTag("oura-raw-statistics-${field.key}"))
                    }
                }
            }
            TextButton(onClick = { details = !details }, modifier = Modifier.testTag("oura-raw-details")) {
                Text(stringResource(R.string.oura_raw_details), style = NoopType.caption)
            }
            if (details) OuraDetailsDialog(stringResource(R.string.oura_raw_details), { details = false }) {
            detailsContent()
            selectedFields.forEach { field ->
                val stats = OuraRawInstruments.statistics(values[field.key].orEmpty().map { it.value })
                if (stats != null) {
                    Text(field.key + " · " + rawUnit(field.unit), style = NoopType.subhead, color = Palette.textPrimary)
                    Text(stringResource(R.string.oura_raw_statistics, rawNumber(stats.latest), rawNumber(stats.minimum), rawNumber(stats.maximum), rawNumber(stats.mean), stats.count),
                        style = NoopType.captionNumber, color = Palette.textSecondary)
                }
            }
            latest?.let { receipt ->
                val distinctTimes = receipts.map { it.receivedAtElapsedMs }.distinct()
                if (distinctTimes.size > 1 && distinctTimes.last() > distinctTimes.first()) Text(stringResource(R.string.oura_raw_rate,
                    rawNumber((distinctTimes.size - 1) * 1000.0 / (distinctTimes.last() - distinctTimes.first())),
                    (distinctTimes.last() - distinctTimes[distinctTimes.lastIndex - 1]).toString()), style = NoopType.captionNumber, color = Palette.textSecondary)
                Text(if (receipt.observation.ringTick == null) stringResource(R.string.oura_raw_no_ring_time)
                    else state.measurementTimes[receipt.observation.ringTick]?.let { epoch ->
                        stringResource(R.string.oura_raw_measurement_time, receipt.observation.ringTick.toString(), java.time.Instant.ofEpochSecond(epoch).toString())
                    } ?: stringResource(R.string.oura_raw_ring_tick, receipt.observation.ringTick.toString()), style = NoopType.captionNumber, color = Palette.textSecondary)
            }
            Text(kind + " · " + stringResource(if (channel.verified) R.string.oura_raw_verified else R.string.oura_raw_research), style = NoopType.caption, color = Palette.textSecondary)
            Text(stringResource(R.string.oura_raw_window, receipts.size), style = NoopType.caption, color = Palette.textSecondary)
            Text(stringResource(R.string.oura_raw_smoothing_info), style = NoopType.caption, color = Palette.textSecondary)
            Text(stringResource(rawInfoLabel(channelKey)), style = NoopType.caption, color = Palette.textSecondary)
            if (channel.acquisition == "poll") {
                Text(stringResource(R.string.oura_raw_timing_caveat), style = NoopType.caption, color = Palette.textTertiary)
                if (state.poll.timingChannel == channelKey) Text(stringResource(R.string.oura_raw_timing,
                    state.poll.queueLatencyMs?.toString() ?: "—", state.poll.writeCompletionMs?.toString() ?: "—", state.poll.responseLatencyMs?.toString() ?: "—"),
                    style = NoopType.captionNumber, color = Palette.textSecondary)
            } else Text(stringResource(if (channel.acquisition == "push") R.string.oura_raw_push_info else R.string.oura_raw_history_info), style = NoopType.caption, color = Palette.textTertiary)
            Text(stringResource(R.string.oura_raw_retention, state.capture.evictedRecords, state.capture.evictedBytes, state.capture.evictedValues, state.capture.truncatedRecords),
                style = NoopType.caption, color = Palette.textTertiary, modifier = Modifier.testTag("oura-raw-last-detail"))
            }
        }
    }
}

@Composable
internal fun <T> OuraRawDropdown(title: String, selection: T, items: List<T>, label: @Composable (T) -> String,
    onSelect: (T) -> Unit, tag: String) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton({ open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.space24 * 2).testTag(tag), shape = RoundedCornerShape(Metrics.cornerXs)) {
            Text(title + ": " + label(selection), style = NoopType.subhead, color = Palette.textPrimary)
        }
        DropdownMenu(open, { open = false }, modifier = Modifier.heightIn(max = Metrics.dialogScrollableMaxHeight)) {
            items.forEach { item -> DropdownMenuItem(text = { Text(label(item), style = NoopType.body) },
                modifier = Modifier.testTag("$tag-$item"), onClick = { open = false; onSelect(item) }) }
        }
    }
}

@Composable
private fun OuraRawGraph(fields: List<OuraRawField>, values: Map<String, List<OuraLivePoint>>, smooth: Boolean, identity: Any, receiptGapMs: Long) {
    val colors = listOf(Palette.accent, Palette.metricAmber, Palette.metricPurple)
    val points = values.values.flatten()
    if (points.isEmpty()) return
    val domainFrom = points.minOf { it.elapsedMs }
    val domainTo = maxOf(domainFrom + 1000, points.maxOf { it.elapsedMs })
    val viewport = rememberOuraChartViewport(domainFrom, domainTo, identity)
    var cursor by remember(identity) { mutableStateOf<Long?>(null) }
    val from = viewport.window.from
    val to = viewport.window.to
    val minimum = points.minOf { it.value }
    val maximum = maxOf(minimum + 1, points.maxOf { it.value })
    val unit = fields.firstOrNull()?.let { rawUnit(it.unit) } ?: ""
    val anchors = points.map { it.elapsedMs }.distinct().sorted().filter { it in from..to }
    fun inspect(at: Long) { cursor = at }
    val inspectLabel = stringResource(R.string.oura_inspect)
    val previous = stringResource(R.string.oura_previous_reading)
    val next = stringResource(R.string.oura_next_reading)
    val zoomActions = ouraChartZoomActions(viewport)
    val description = fields.joinToString(" / ") { it.key }
    val inspection = fields.map { field ->
        val readings = cursor?.let { ouraLiveInspect(values[field.key].orEmpty(), it, receiptGapMs) }.orEmpty()
        field.key + ": " + if (readings.isEmpty()) "—" else readings.joinToString(" / ") { rawNumber(it.value) + " " + unit }
    }
    val secondsFormat = stringResource(R.string.oura_chart_seconds)
    OuraChartLegend(fields.mapIndexed { index, field -> OuraLegendEntry(field.key, field.key, rawUnit(field.unit), colors[index % colors.size], ouraLiveTraceStyle(field.key), null) })
    OuraChartFrame("oura-raw-plot", listOf(OuraChartScale(description, unit, minimum, maximum, colors.first())), viewport,
        stringResource(R.string.oura_delivery_axis), { ouraChartSeconds((it - domainTo) / 1000.0, secondsFormat) }) { modifier ->
    Spacer(modifier.testTag("oura-raw-plot").semantics {
        contentDescription = description
        stateDescription = inspection.joinToString()
        onClick(label = inspectLabel) { if (cursor == null) inspect(viewport.at(.5f)); true }
        customActions = zoomActions + listOf(
            CustomAccessibilityAction(previous) {
                val target = if (cursor == null) anchors.lastOrNull() else anchors.lastOrNull { it < cursor!! }
                if (target == null) false else { cursor = target; true }
            },
            CustomAccessibilityAction(next) {
                val target = if (cursor == null) anchors.firstOrNull() else anchors.firstOrNull { it > cursor!! }
                if (target == null) false else { cursor = target; true }
            })
    }.ouraChartGestures(viewport, ::inspect).drawWithCache {
        val stroke = Metrics.space2.toPx()
        fun x(time: Long) = ((time - from).toDouble() / (to - from) * size.width).toFloat()
        val paths = fields.map { field ->
            val raw = values[field.key].orEmpty()
            val display = if (!smooth) raw else if (field.key == "hr") ouraLiveHrTrend(raw) else ouraLiveMotionTrend(raw)
            ouraLiveSegments(display, from, to, size.width.toInt(), receiptGapMs).map { segment ->
                Path().apply { segment.forEachIndexed { index, point ->
                    val px = x(point.elapsedMs)
                    val y = ((1 - (point.value - minimum) / (maximum - minimum)) * size.height).toFloat()
                    if (index == 0) moveTo(px, y) else lineTo(px, y)
                    if (segment.size == 1) lineTo(px + stroke, y)
                } }
            }
        }
        onDrawBehind { clipRect {
            for (fraction in listOf(0f, .5f, 1f)) drawLine(Palette.hairline, Offset(0f, fraction * size.height), Offset(size.width, fraction * size.height))
            paths.forEachIndexed { index, segments -> segments.forEach {
                drawPath(it, colors[index % colors.size], style = ouraTraceStroke(ouraLiveTraceStyle(fields[index].key)))
            } }
            cursor?.let { drawLine(Palette.textSecondary, Offset(x(it), 0f), Offset(x(it), size.height), stroke) }
        } }
    })
    }
    Column(Modifier.testTag("oura-raw-plot-readout"), verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
        if (cursor == null) Text(inspectLabel, style = NoopType.caption, color = Palette.textSecondary)
        else {
            Text(stringResource(R.string.oura_delivery_axis) + " · " + ouraChartSeconds((cursor!! - domainTo) / 1000.0, secondsFormat),
                style = NoopType.captionNumber, color = Palette.textSecondary)
            inspection.forEach { Text(it, style = NoopType.captionNumber, color = Palette.textPrimary) }
        }
    }
}

private fun rawNumber(value: Double) = String.format(Locale.getDefault(), "%.2f", value)
@Composable private fun rawUnit(unit: String): String = when (unit) {
    "live counts" -> stringResource(R.string.oura_live_raw_counts)
    "record counts ×8" -> stringResource(R.string.oura_raw_record_counts)
    "optical counts" -> stringResource(R.string.oura_raw_optical_counts)
    else -> unit
}
@Composable private fun rawChannelLabel(channel: OuraRawChannel): String = when (channel.key) {
    "event:80" -> stringResource(R.string.oura_raw_green)
    "event:60" -> stringResource(R.string.oura_raw_night)
    "event:46" -> stringResource(R.string.oura_raw_temp)
    "event:47" -> stringResource(R.string.oura_raw_motion)
    "event:5d" -> stringResource(R.string.oura_raw_hrv)
    "event:8b" -> stringResource(R.string.oura_raw_r)
    "event:81" -> stringResource(R.string.oura_raw_ppg)
    "hr" -> stringResource(R.string.oura_hr)
    "motion" -> stringResource(R.string.oura_raw_live_xyz)
    "battery" -> stringResource(R.string.oura_raw_battery)
    "status:02" -> stringResource(R.string.oura_raw_status_day)
    "status:04" -> stringResource(R.string.oura_raw_status_oxygen)
    "status:0b" -> stringResource(R.string.oura_raw_status_steps)
    else -> channel.meaning.ifEmpty { stringResource(R.string.oura_raw_unknown) }
}
private fun rawPollPhaseLabel(phase: OuraRawPollPhase) = when (phase) {
    OuraRawPollPhase.STOPPED -> R.string.oura_live_stopped
    OuraRawPollPhase.OBSERVING -> R.string.oura_raw_observing
    OuraRawPollPhase.WAITING -> R.string.oura_raw_wait_boundary
    OuraRawPollPhase.QUEUED -> R.string.oura_raw_queued
    OuraRawPollPhase.OUTSTANDING -> R.string.oura_raw_outstanding
    OuraRawPollPhase.PACING -> R.string.oura_raw_pacing
    OuraRawPollPhase.STALLED -> R.string.oura_raw_stalled
}

private fun rawInfoLabel(channelKey: String): Int = when (channelKey) {
    "event:80" -> R.string.oura_raw_quality_info
    "event:60", "event:44" -> R.string.oura_raw_night_info
    "event:8b" -> R.string.oura_raw_r_info
    "event:81" -> R.string.oura_raw_ppg_info
    "event:47" -> R.string.oura_raw_motion_info
    else -> R.string.oura_raw_scope_info
}
