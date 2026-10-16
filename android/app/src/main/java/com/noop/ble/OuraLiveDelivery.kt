package com.noop.ble

/** Host delivery evidence for the retained Live window; none of these clocks date a ring measurement. */
internal data class OuraLiveDelivery(
    val readings: Int,
    val notifications: Int?,
    val maxReadingsPerNotification: Int?,
    val lastReceiveGapMs: Long?,
    val minReceiveGapMs: Long?,
    val maxReceiveGapMs: Long?,
    val lastQueueWaitMs: Long?,
    val maxQueueWaitMs: Long?,
    val outOfOrderReceipts: Int,
)

internal fun ouraLiveDelivery(samples: List<OuraLiveHrSample>): OuraLiveDelivery {
    val gaps = samples.zipWithNext { a, b -> b.receivedAtElapsedMs - a.receivedAtElapsedMs }
    val validGaps = gaps.filter { it >= 0 }
    val groups = if (samples.isNotEmpty() && samples.all { it.notificationOrdinal != null })
        samples.groupingBy { it.notificationOrdinal }.eachCount() else null
    fun wait(sample: OuraLiveHrSample): Long? = sample.processedAtElapsedMs?.let { processed ->
        (processed - sample.receivedAtElapsedMs).takeIf { it >= 0 }
    }
    return OuraLiveDelivery(samples.size, groups?.size, groups?.values?.maxOrNull(),
        gaps.lastOrNull()?.takeIf { it >= 0 }, validGaps.minOrNull(), validGaps.maxOrNull(),
        samples.lastOrNull()?.let(::wait), samples.mapNotNull(::wait).maxOrNull(), gaps.count { it < 0 })
}
