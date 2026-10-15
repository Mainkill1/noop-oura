package com.noop.data

import com.noop.analytics.SignalDecimation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.coroutineContext

data class OuraDashboardSource(val id: String, val name: String) {
    val isImport: Boolean get() = id == "oura-import" || id == "oura-api"
}

data class OuraDashboardData(
    val signals: Map<String, SignalDecimation.Summary> = emptyMap(),
    val sleeps: List<SleepSession> = emptyList(),
    val motionWindows: List<OuraMotionWindow> = emptyList(),
    val liveMotionSeconds: List<OuraLiveMotionHistory> = emptyList(),
) {
    companion object {
        val importedKeys = listOf("rhr", "hrv", "spo2", "resp_rate", "skin_temp_dev_c", "steps", "energy_kcal",
            "total_kcal", "sleep_total_min", "sleep_deep_min", "sleep_rem_min",
            "ref_readiness_score", "ref_sleep_score", "ref_activity_score", "vo2max")

        /** Read one source only, off the UI thread. A storage error is never an empty success. */
        suspend fun load(repo: WhoopRepository, source: OuraDashboardSource,
                         from: Long, to: Long, bucketSeconds: Long): OuraDashboardData = withContext(Dispatchers.Default) {
            val signals = linkedMapOf<String, SignalDecimation.Summary>()
            val recentMotion = java.util.ArrayDeque<OuraMotionWindow>()
            val recentLiveMotion = java.util.ArrayDeque<OuraLiveMotionHistory>()
            val limit = 200_001
            fun <T> checked(rows: List<T>): List<T> {
                check(rows.size < limit) { "Oura read limit reached" }
                return rows
            }
            if (source.isImport) {
                val zone = ZoneId.systemDefault()
                val first = Instant.ofEpochSecond(from).atZone(zone).toLocalDate().toString()
                val last = Instant.ofEpochSecond(to - 1).atZone(zone).toLocalDate().toString()
                val daily = repo.dailyMetrics(source.id, first, last)
                for (key in importedKeys) {
                    coroutineContext.ensureActive()
                    var summary = SignalDecimation.Summary()
                    val byDay = repo.metricSeries(source.id, key, first, last).associate { it.day to it.value }.toMutableMap()
                    for (row in daily) if (row.day !in byDay) dailyValue(row, key)?.let { byDay[row.day] = it }
                    for ((dayKey, value) in byDay.toSortedMap()) {
                        val day = LocalDate.parse(dayKey)
                        val start = day.atStartOfDay(zone).toEpochSecond()
                        val end = day.plusDays(1).atStartOfDay(zone).toEpochSecond()
                        summary = summary.append(SignalDecimation.reduce(
                            listOf(SignalDecimation.Sample(start, value)), start, end, end - start))
                    }
                    signals[key] = summary
                }
            } else {
                var start = from
                while (start < to) {
                    coroutineContext.ensureActive()
                    val end = minOf(to, start + 86_400)
                    val raw = linkedMapOf<String, List<SignalDecimation.Sample>>()
                    raw["hr"] = checked(repo.hrSamplesForDevice(source.id, start, end - 1, limit))
                        .filter { it.bpm in 1..300 }.map { SignalDecimation.Sample(it.ts, it.bpm.toDouble()) }
                    raw["oxygen"] = checked(repo.spo2Samples(source.id, start, end - 1, limit))
                        .filter { it.ir <= 0 && it.red in com.noop.analytics.AnalyticsEngine.SPO2_SINGLE_CHANNEL_PLAUSIBLE }
                        .map { SignalDecimation.Sample(it.ts, it.red.toDouble()) }
                    raw["temperature"] = checked(repo.skinTempSamples(source.id, start, end - 1, limit))
                        .map { SignalDecimation.Sample(it.ts, it.raw / 100.0) }
                    raw["breathing"] = checked(repo.respSamples(source.id, start, end - 1, limit))
                        .map { SignalDecimation.Sample(it.ts, OuraRespScale.displayValue(it.raw, source.id)) }
                    raw["battery"] = checked(repo.batterySamples(source.id, start, end - 1, limit))
                        .mapNotNull { row -> row.soc?.let { SignalDecimation.Sample(row.ts, it) } }
                    val events = checked(repo.events(source.id, start, end - 1, limit))
                    raw["hrv"] = events.filter { it.kind == OuraStreamMapping.EVENT_HRV }.mapNotNull {
                        val value = runCatching { JSONObject(it.payloadJSON).optDouble("rmssd_ms", Double.NaN) }.getOrDefault(Double.NaN)
                        if (value > 0) SignalDecimation.Sample(it.ts, value) else null
                    }
                    val liveMotion = events.filter { it.kind == com.noop.oura.OuraLiveMotionSummary.EVENT_KIND }
                        .mapNotNull { OuraLiveMotionHistory.parse(it.ts, it.payloadJSON) }
                    val liveSignals = linkedMapOf<String, MutableList<SignalDecimation.Sample>>()
                    for (record in liveMotion) {
                        recentLiveMotion.addLast(record)
                        if (recentLiveMotion.size > 120) recentLiveMotion.removeFirst()
                        fun add(key: String, value: Double) { liveSignals.getOrPut(key) { mutableListOf() }.add(SignalDecimation.Sample(record.ts, value)) }
                        add("live_motion_count", record.count.toDouble())
                        for (axis in listOf("x", "y", "z")) {
                            add("live_motion_$axis", record.mean(axis))
                            add("live_motion_${axis}_min", record.min(axis).toDouble())
                            add("live_motion_${axis}_max", record.max(axis).toDouble())
                        }
                    }
                    raw.putAll(liveSignals)
                    val motion = events.filter { it.kind == OuraStreamMapping.EVENT_MOTION }
                        .mapNotNull { OuraMotionWindow.parse(it.ts, it.payloadJSON) }
                    for (record in motion) {
                        recentMotion.addLast(record)
                        if (recentMotion.size > 24) recentMotion.removeFirst()
                    }
                    val motionSignals = listOf("movement", "motion_x", "motion_y", "motion_z", "motion_low", "motion_high")
                        .associateWith { mutableListOf<SignalDecimation.Sample>() }
                    for (record in motion) record.values().forEach { (metric, value) ->
                        motionSignals.getValue(metric).add(SignalDecimation.Sample(record.ts, value))
                    }
                    raw.putAll(motionSignals)
                    for ((key, samples) in raw) signals[key] = (signals[key] ?: SignalDecimation.Summary())
                        .append(SignalDecimation.reduce(samples, start, end, bucketSeconds))
                    start = end
                }
            }
            val sleeps = repo.sleepSessionsForDevice(source.id, from - 172_800, to - 1, 2001)
            check(sleeps.size < 2001) { "Oura sleep read limit reached" }
            OuraDashboardData(signals, sleeps.filter { it.endTs >= from && it.endTs < to }.sortedByDescending { it.endTs }, recentMotion.toList().asReversed(), recentLiveMotion.toList().asReversed())
        }

        private fun dailyValue(row: DailyMetric, key: String): Double? = when (key) {
            "rhr" -> row.restingHr?.toDouble(); "hrv" -> row.avgHrv; "spo2" -> row.spo2Pct
            "resp_rate" -> row.respRateBpm; "skin_temp_dev_c" -> row.skinTempDevC
            "steps" -> row.steps?.toDouble(); "energy_kcal" -> row.activeKcalEst
            "sleep_total_min" -> row.totalSleepMin; "sleep_deep_min" -> row.deepMin; "sleep_rem_min" -> row.remMin
            else -> null
        }
    }
}
