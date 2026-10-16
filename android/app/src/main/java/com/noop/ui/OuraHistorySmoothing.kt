package com.noop.ui

import com.noop.analytics.SignalDecimation

/** Android chart presentation only: a trailing mean of medians, reset at every missing bucket. */
internal fun ouraHistoryTrend(buckets: List<SignalDecimation.Bucket>, strength: Int): List<Double> {
    val width = listOf(1, 3, 5, 9, 15)[strength.coerceIn(0, 4)]
    val window = java.util.ArrayDeque<Double>()
    var total = 0.0
    var end: Long? = null
    return buckets.map { bucket ->
        if (end != bucket.start) { window.clear(); total = 0.0 }
        window.addLast(bucket.median); total += bucket.median
        if (window.size > width) total -= window.removeFirst()
        end = bucket.end
        total / window.size
    }
}
