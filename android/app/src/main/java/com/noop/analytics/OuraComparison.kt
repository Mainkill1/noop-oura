package com.noop.analytics

/** Display-only domains and inspection: incompatible quantities never acquire a common numerical scale. */
object OuraComparison {
    data class Trace(val id: String, val dimension: String, val unit: String,
                     val summary: SignalDecimation.Summary, val scale: Double = 1.0, val offset: Double = 0.0) {
        val axisId: String get() = "$dimension|$unit"
        fun display(value: Double): Double = value * scale + offset
    }
    data class Axis(val minimum: Double, val maximum: Double)

    fun toggle(selected: List<String>, id: String): List<String> = when {
        id in selected -> selected - id
        selected.size < 4 -> selected + id
        else -> selected
    }

    fun axes(traces: List<Trace>): Map<String, Axis> {
        require(traces.size <= 4)
        require(traces.all { it.scale.isFinite() && it.scale > 0 && it.offset.isFinite() })
        return traces.filter { it.summary.count > 0 && it.summary.minimum != null && it.summary.maximum != null }
            .groupBy { it.axisId }.mapNotNull { (id, group) ->
                val low = group.minOf { it.display(it.summary.minimum!!) }
                val high = group.maxOf { it.display(it.summary.maximum!!) }
                val padding = maxOf((high - low) * 0.08, 0.5)
                val axis = Axis(low - padding, high + padding)
                if (axis.minimum.isFinite() && axis.maximum.isFinite() &&
                    (axis.maximum - axis.minimum).isFinite() && axis.maximum > axis.minimum) id to axis else null
            }.toMap()
    }

    fun axisFor(trace: Trace, axes: Map<String, Axis>): Axis? = axes[trace.axisId]

    fun indexAt(buckets: List<SignalDecimation.Bucket>, ts: Long): Int {
        var low = 0
        var high = buckets.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val bucket = buckets[mid]
            when {
                ts < bucket.start -> high = mid - 1
                ts >= bucket.end -> low = mid + 1
                else -> return mid
            }
        }
        return -1
    }
    fun bucketAt(buckets: List<SignalDecimation.Bucket>, ts: Long): SignalDecimation.Bucket? = buckets.getOrNull(indexAt(buckets, ts))
}

/** A current stream needs both source evidence and a recent, nonfuture original observation. */
object OuraLivePresentation {
    fun isFresh(selectedActive: Boolean, connected: Boolean, streaming: Boolean, lastTs: Long?, now: Long): Boolean =
        selectedActive && connected && streaming && lastTs != null && now - lastTs in 0..30

    fun window(now: Long): Pair<Long, Long> = (Math.floorDiv(now, 5) * 5 - 900) to (now + 1)
}
