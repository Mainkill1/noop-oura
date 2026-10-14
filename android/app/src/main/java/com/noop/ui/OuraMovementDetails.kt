package com.noop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.noop.R
import com.noop.data.OuraMotionWindow
import java.time.Instant
import java.util.Locale

/** A bounded inspector for the ring's recorded summary windows, including categorical orientation. */
@Composable
internal fun OuraMovementDetails(records: List<OuraMotionWindow>) {
    val time = rememberOuraTimestampFormatter()
    var selectedTs by rememberSaveable { mutableStateOf<Long?>(null) }
    val index = records.indexOfFirst { it.ts == selectedTs }.coerceAtLeast(0)
    val record = records.getOrNull(index)
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Text(stringResource(R.string.oura_motion_details), style = NoopType.headline, color = Palette.textPrimary)
            Text(stringResource(R.string.oura_motion_info), style = NoopType.caption, color = Palette.textSecondary)
            if (record == null) Text(stringResource(R.string.oura_no_readings), style = NoopType.body, color = Palette.textSecondary)
            else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selectedTs = records[index + 1].ts }, enabled = index < records.lastIndex) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.oura_previous_reading))
                    }
                    Text(time.format(Instant.ofEpochSecond(record.ts)), Modifier.weight(1f), style = NoopType.bodyNumber, color = Palette.textPrimary)
                    IconButton(onClick = { selectedTs = records[index - 1].ts }, enabled = index > 0) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.oura_next_reading))
                    }
                }
                val entries = listOf(
                    R.string.oura_motion_seconds to record.seconds, R.string.oura_motion_x to record.x,
                    R.string.oura_motion_y to record.y, R.string.oura_motion_z to record.z,
                    R.string.oura_motion_low to record.low, R.string.oura_motion_high to record.high,
                    R.string.oura_motion_orientation to record.orientation?.toDouble())
                entries.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                        Text(stringResource(label), Modifier.weight(1f), style = NoopType.caption, color = Palette.textSecondary)
                        Text(value?.let { String.format(Locale.getDefault(), "%.0f", it) } ?: "—",
                            style = NoopType.bodyNumber, color = Palette.textPrimary)
                    }
                }
                Text(stringResource(R.string.oura_motion_recent, records.size), style = NoopType.caption, color = Palette.textTertiary)
            }
        }
    }
}
