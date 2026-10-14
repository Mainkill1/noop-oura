package com.noop.analytics

/** Display-only reduction; Kotlin twin of StrandAnalytics/SignalDecimation.swift. */
object SignalDecimation {
    data class Sample(val ts: Long, val value: Double)
    data class Bucket(
        val start: Long, val end: Long, val median: Double,
        val minimum: Double, val maximum: Double, val count: Int,
    )
    data class Summary(
        val buckets: List<Bucket> = emptyList(), val count: Int = 0, val sum: Double = 0.0,
        val minimum: Double? = null, val maximum: Double? = null, val latestTs: Long? = null,
    ) {
        val mean: Double? get() = if (count > 0) sum / count else null

        /** Batches must cover disjoint, bucket-aligned windows in ascending order. */
        fun append(other: Summary) = Summary(
            buckets + other.buckets, count + other.count, sum + other.sum,
            listOfNotNull(minimum, other.minimum).minOrNull(),
            listOfNotNull(maximum, other.maximum).maxOrNull(),
            listOfNotNull(latestTs, other.latestTs).maxOrNull(),
        )
    }

    fun reduce(samples: List<Sample>, from: Long, to: Long, bucketSeconds: Long): Summary {
        if (to <= from) return Summary()
        val width = bucketSeconds.coerceAtLeast(1)
        val valid = samples.filter { it.ts >= from && it.ts < to && it.value.isFinite() }
        val buckets = valid.groupBy { from + ((it.ts - from) / width) * width }
            .toSortedMap().map { (start, points) ->
                val sorted = points.map { it.value }.sorted()
                val middle = sorted.size / 2
                val median = if (sorted.size % 2 == 0) sorted[middle - 1] / 2 + sorted[middle] / 2
                    else sorted[middle]
                Bucket(start, minOf(to, start + width), median, sorted.first(), sorted.last(), sorted.size)
            }
        return Summary(buckets, valid.size, valid.sumOf { it.value },
            valid.minOfOrNull { it.value }, valid.maxOfOrNull { it.value }, valid.maxOfOrNull { it.ts })
    }
}
