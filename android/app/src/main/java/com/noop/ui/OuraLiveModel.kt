package com.noop.ui

import com.noop.ble.*
import kotlin.math.abs

internal fun ouraLiveSelection(category: OuraCategory, comparison: Set<String>) = when (category) {
    OuraCategory.Health -> OuraLiveSelection(hr = true)
    OuraCategory.Movement -> OuraLiveSelection(motion = true)
    OuraCategory.Compare -> OuraLiveSelection(hr = "hr" in comparison, motion = comparison.any { it in setOf("x", "y", "z") })
    OuraCategory.Sleep -> OuraLiveSelection()
}

internal enum class OuraLiveAction { Start, Update, Stop }
internal data class OuraLiveIntent(val action: OuraLiveAction, val sourceId: String, val generation: Long,
    val selection: OuraLiveSelection = OuraLiveSelection())

/** Holds only this screen's accepted ownership alias. Transport owns all scheduling and reconciliation. */
internal class OuraLiveOwner(private val emit: (OuraLiveIntent) -> Unit) {
    private var owner: Pair<String, Long>? = null
    private var desired = OuraLiveSelection()
    val hasOwnership: Boolean get() = owner != null
    fun start(snapshot: OuraLiveSessionSnapshot, selection: OuraLiveSelection) {
        if (owner != null || snapshot.active || (!selection.hr && !selection.motion)) return
        val id = snapshot.sourceDeviceId ?: return
        owner = id to snapshot.generation
        desired = selection
        emit(OuraLiveIntent(OuraLiveAction.Start, id, snapshot.generation, selection))
    }
    fun observe(snapshot: OuraLiveSessionSnapshot) {
        val owned = owner ?: return
        if (snapshot.sourceDeviceId != owned.first || (!snapshot.active && snapshot.generation != owned.second)) owner = null
    }
    fun update(selection: OuraLiveSelection) {
        val owned = owner ?: return
        if (selection == desired) return
        desired = selection
        emit(OuraLiveIntent(OuraLiveAction.Update, owned.first, owned.second, selection))
    }
    fun stop() {
        val owned = owner ?: return
        owner = null
        emit(OuraLiveIntent(OuraLiveAction.Stop, owned.first, owned.second))
    }
}

internal data class OuraLivePoint(val elapsedMs: Long, val value: Double)
internal enum class OuraLiveReadingState { Waiting, Receiving, Stale, Unavailable, Disconnected, Stopped }
internal fun ouraLiveAgeSeconds(receipt: Long?, now: Long): Long? = receipt?.let { ((now - it).coerceAtLeast(0)) / 1000 }
internal fun ouraLiveReadingState(active: Boolean, connected: Boolean, phase: OuraLiveStreamPhase,
    receipt: Long?, now: Long, staleAfterMs: Long) = when {
    !connected -> OuraLiveReadingState.Disconnected
    !active || phase == OuraLiveStreamPhase.IDLE -> OuraLiveReadingState.Stopped
    phase == OuraLiveStreamPhase.UNAVAILABLE -> OuraLiveReadingState.Unavailable
    receipt == null -> OuraLiveReadingState.Waiting
    now - receipt > staleAfterMs || phase == OuraLiveStreamPhase.STALE -> OuraLiveReadingState.Stale
    else -> OuraLiveReadingState.Receiving
}
internal fun ouraLiveSnapshotForSource(snapshot: OuraLiveSessionSnapshot, sourceId: String): OuraLiveSessionSnapshot =
    if (snapshot.sourceDeviceId == sourceId) snapshot else OuraLiveSessionSnapshot()
internal fun ouraLivePlotEnd(snapshot: OuraLiveSessionSnapshot, now: Long): Long = if (snapshot.active) now else
    maxOf(snapshot.hr.lastReceiptElapsedMs ?: 0, snapshot.motion.lastReceiptElapsedMs ?: 0,
        snapshot.startedAtElapsedMs ?: 0)

/** Display-only three-second trailing mean, reset after a missing HR interval. */
internal fun ouraLiveHrTrend(raw: List<OuraLivePoint>): List<OuraLivePoint> {
    val window = java.util.ArrayDeque<OuraLivePoint>()
    var sum = 0.0
    var last: Long? = null
    return raw.map { point ->
        if (last?.let { point.elapsedMs - it > 5000 } == true) { window.clear(); sum = 0.0 }
        while (window.isNotEmpty() && window.first.elapsedMs < point.elapsedMs - 3000) sum -= window.removeFirst().value
        window.addLast(point); sum += point.value; last = point.elapsedMs
        point.copy(value = sum / window.size)
    }
}

/** Each pixel bin retains first, minimum, maximum, last in sample order, after splitting real gaps. */
internal fun ouraLiveSegments(raw: List<OuraLivePoint>, from: Long, to: Long, pixels: Int,
    gapMs: Long): List<List<OuraLivePoint>> {
    val segments = mutableListOf<MutableList<OuraLivePoint>>()
    for (point in raw) {
        if (point.elapsedMs !in from..to) continue
        if (segments.isEmpty() || point.elapsedMs - segments.last().last().elapsedMs > gapMs) segments.add(mutableListOf())
        segments.last().add(point)
    }
    val duration = (to - from).coerceAtLeast(1)
    return segments.map { segment ->
        segment.withIndex().groupBy { ((it.value.elapsedMs - from) * pixels.coerceAtLeast(1) / duration).coerceAtMost(pixels.toLong()) }
            .values.flatMap { bin ->
                listOf(bin.first(), bin.minBy { it.value.value }, bin.maxBy { it.value.value }, bin.last())
                    .distinctBy { it.index }.sortedBy { it.index }.map { it.value }
            }
    }
}

/** No interpolation: every vector sharing the nearest actual receipt remains inspectable. */
internal fun ouraLiveInspect(raw: List<OuraLivePoint>, at: Long, toleranceMs: Long): List<OuraLivePoint> {
    val nearest = raw.minByOrNull { abs(it.elapsedMs - at) } ?: return emptyList()
    if (abs(nearest.elapsedMs - at) > toleranceMs / 2) return emptyList()
    return raw.filter { it.elapsedMs == nearest.elapsedMs }
}
