package com.noop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.noop.R
import com.noop.data.OuraDashboardData
import com.noop.data.OuraLiveMotionHistory
import java.time.Instant
import java.util.Locale

/** Recorded context has its own timestamps and never supplies live receipt/freshness evidence. */
@Composable
internal fun OuraRecordedHealth(data: OuraDashboardData, temp: TemperatureUnit, onHistory: () -> Unit) {
    Column(Modifier.testTag("oura-recorded-health"), verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.oura_recorded_health), Modifier.weight(1f), style = NoopType.subhead, color = Palette.textPrimary)
            TextButton(onClick = onHistory) { Text(stringResource(R.string.oura_history)) }
        }
        OuraStatsOverview(data, ouraCategoryKeys(false, OuraCategory.Health), false, temp, isLive = true)
    }
}

/** Bounded inspection of stored seconds; full-range means remain in the History graphs. */
@Composable
internal fun OuraLiveMotionDetails(records: List<OuraLiveMotionHistory>) {
    var selectedTs by rememberSaveable { mutableStateOf<Long?>(null) }
    val index = records.indexOfFirst { it.ts == selectedTs }.coerceAtLeast(0)
    val record = records.getOrNull(index)
    val time = rememberOuraTimestampFormatter(seconds = true)
    OuraPanel {
        Column(Modifier.testTag("oura-host-motion-details"), verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
            Text(stringResource(R.string.oura_host_motion), style = NoopType.subhead, color = Palette.textPrimary)
            Text(stringResource(R.string.oura_host_motion_info), style = NoopType.caption, color = Palette.textSecondary)
            if (record == null) Text(stringResource(R.string.oura_no_readings), style = NoopType.body, color = Palette.textSecondary)
            else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selectedTs = records[index + 1].ts }, enabled = index < records.lastIndex) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.oura_previous_reading))
                    }
                    Text(time.format(Instant.ofEpochSecond(record.ts)), Modifier.weight(1f), style = NoopType.bodyNumber, color = Palette.textPrimary)
                    IconButton(onClick = { selectedTs = records[index - 1].ts }, enabled = index > 0) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.oura_next_reading))
                    }
                }
                Text(stringResource(R.string.oura_host_sample_count, record.count), style = NoopType.bodyNumber, color = Palette.textPrimary)
                for (axis in listOf("x", "y", "z")) {
                    val title = stringResource(when (axis) { "x" -> R.string.oura_motion_x; "y" -> R.string.oura_motion_y; else -> R.string.oura_motion_z })
                    Text(stringResource(R.string.oura_host_axis_detail, title,
                        String.format(Locale.getDefault(), "%.1f", record.mean(axis)), record.min(axis), record.max(axis)),
                        style = NoopType.captionNumber, color = Palette.textPrimary)
                }
                Text(stringResource(R.string.oura_host_receipt_bounds,
                    time.format(Instant.ofEpochMilli(record.firstReceiptEpochMs)), time.format(Instant.ofEpochMilli(record.lastReceiptEpochMs))),
                    style = NoopType.captionNumber, color = Palette.textSecondary, modifier = Modifier.testTag("oura-host-last-detail"))
            }
        }
    }
}

/** Per-second mean traces carry the actual stored minima/maxima as their display envelope. */
internal fun ouraRecordedDisplaySignals(signals: Map<String, com.noop.analytics.SignalDecimation.Summary>): Map<String, com.noop.analytics.SignalDecimation.Summary> =
    signals.mapValues { (key, mean) ->
        if (key !in listOf("live_motion_x", "live_motion_y", "live_motion_z")) mean else {
            val low = signals["${key}_min"]
            val high = signals["${key}_max"]
            val lows = low?.buckets.orEmpty().associateBy { it.start }
            val highs = high?.buckets.orEmpty().associateBy { it.start }
            mean.copy(minimum = low?.minimum ?: mean.minimum, maximum = high?.maximum ?: mean.maximum,
                buckets = mean.buckets.map { bucket -> bucket.copy(
                    minimum = lows[bucket.start]?.minimum ?: bucket.minimum,
                    maximum = highs[bucket.start]?.maximum ?: bucket.maximum) })
        }
    }
