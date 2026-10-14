package com.noop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.noop.R
import com.noop.analytics.SleepStageTotals
import com.noop.data.SleepSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/** One lazy item per night; parse only visible nights, away from the UI thread. */
@Composable
internal fun OuraSleepCard(sleep: SleepSession) {
    val parsed by produceState<Pair<SleepStageTotals.Minutes?, List<PersistedSegment>>?>(null, sleep) {
        value = null
        value = withContext(Dispatchers.Default) {
            val clamped = SleepStageTotals.clampStagesToOnset(sleep.stagesJSON, sleep.effectiveStartTs)
            val stages = runCatching {
                val array = JSONArray(sleep.stagesJSON ?: "[]")
                (0 until array.length()).mapNotNull { index ->
                    val stage = array.getJSONObject(index)
                    val start = maxOf(stage.getLong("start"), sleep.effectiveStartTs)
                    val end = minOf(stage.getLong("end"), sleep.endTs)
                    val name = stage.getString("stage").let { if (it == "wake") "awake" else it }
                    if (end > start && name in listOf("awake", "light", "deep", "rem")) PersistedSegment(start, end, name) else null
                }
            }.getOrDefault(emptyList())
            SleepStageTotals.minutes(clamped) to stages
        }
    }
    val totals = parsed?.first
    val format = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()) }
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Text(format.format(Instant.ofEpochSecond(sleep.effectiveStartTs)) + " – " + format.format(Instant.ofEpochSecond(sleep.endTs)),
                style = NoopType.captionNumber, color = Palette.textSecondary)
            totals?.let {
                Text(stringResource(R.string.oura_sleep_totals, it.asleep.roundToInt(), it.deep.roundToInt(), it.light.roundToInt(), it.rem.roundToInt()),
                    style = NoopType.captionNumber, color = Palette.textSecondary)
            }
            val stages = parsed?.second
            when {
                stages == null -> Box(Modifier.fillMaxWidth().height(Metrics.compactChartHeight)) {
                    Text(stringResource(R.string.oura_loading), style = NoopType.caption, color = Palette.textSecondary)
                }
                stages.isNotEmpty() -> FilledHypnogram(stages, sleep.effectiveStartTs, sleep.endTs, filled = false)
                else -> Text(stringResource(R.string.oura_no_stages), style = NoopType.caption, color = Palette.textSecondary)
            }
        }
    }
}
