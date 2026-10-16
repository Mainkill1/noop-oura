package com.noop.ui

import com.noop.ble.*
import kotlin.math.abs

internal fun ouraLiveSelection(category: OuraCategory, comparison: Set<String>) = when (category) {
    OuraCategory.Health -> OuraLiveSelection(hr = true)
    OuraCategory.Movement -> OuraLiveSelection(motion = true)
    OuraCategory.Compare -> OuraLiveSelection(hr = "hr" in comparison, motion = comparison.any { it in setOf("x", "y", "z") })
    OuraCategory.Sleep, OuraCategory.Raw -> OuraLiveSelection()
}

internal enum class OuraLiveAction { Start, Update, Stop }
internal data class OuraLiveIntent(val action: OuraLiveAction, val sourceId: String, val generation: Long,
    val selection: OuraLiveSelection = OuraLiveSelection())

/** Holds only this screen's accepted ownership alias. Transport owns all scheduling and reconciliation. */
internal class OuraLiveOwner(private val emit: (OuraLiveIntent) -> Unit) {
    private data class Token(val sourceId: String, val incarnation: Long, val generation: Long) {
        val identity: Pair<String, Long> get() = sourceId to incarnation
    }
    private fun token(snapshot: OuraLiveSessionSnapshot): Token? = snapshot.sourceDeviceId?.let {
        Token(it, snapshot.sourceIncarnation, snapshot.generation)
    }
    private var owner: Token? = null
    private var desired = OuraLiveSelection()
    val hasOwnership: Boolean get() = owner != null
    private var entry: Pair<String, Long>? = null
    private var awaitingIdle = false
    private var observed: Token? = null
    private var stopped: Token? = null
    /** Only visibility, authenticated source incarnation and desired channels can create a request. */
    fun present(snapshot: OuraLiveSessionSnapshot, sourceId: String, visible: Boolean,
        ready: Boolean, selection: OuraLiveSelection) {
        observed = token(snapshot)
        val next = observed?.identity?.takeIf {
            visible && ready && snapshot.sourceDeviceId == sourceId && (selection.hr || selection.motion)
        }
        if (next != entry) {
            stop()
            entry = next
            awaitingIdle = next != null && (snapshot.active || stopped == observed)
            if (next != null && !awaitingIdle) start(snapshot, selection)
        } else if (next != null) {
            if (awaitingIdle) {
                if (!snapshot.active && stopped != observed) { awaitingIdle = false; start(snapshot, selection) }
                return
            }
            observe(snapshot)
            update(selection)
        }
    }
    fun hide() { entry = null; awaitingIdle = false; stop() }
    fun start(snapshot: OuraLiveSessionSnapshot, selection: OuraLiveSelection) {
        if (owner != null || snapshot.active || (!selection.hr && !selection.motion)) return
        val accepted = token(snapshot) ?: return
        owner = accepted
        desired = selection
        emit(OuraLiveIntent(OuraLiveAction.Start, accepted.sourceId, accepted.generation, selection))
    }
    fun observe(snapshot: OuraLiveSessionSnapshot) {
        observed = token(snapshot)
        val owned = owner ?: return
        if (observed?.identity != owned.identity || (!snapshot.active && snapshot.generation != owned.generation)) owner = null
    }
    fun update(selection: OuraLiveSelection) {
        val owned = owner ?: return
        if (selection == desired) return
        desired = selection
        emit(OuraLiveIntent(OuraLiveAction.Update, owned.sourceId, owned.generation, selection))
    }
    fun stop() {
        val owned = owner ?: return
        stopped = observed?.takeIf { it.identity == owned.identity } ?: owned
        owner = null
        emit(OuraLiveIntent(OuraLiveAction.Stop, owned.sourceId, owned.generation))
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

/** Display-only 250 ms trailing mean of actual receipts; same-receipt vectors remain separate. */
internal fun ouraLiveMotionTrend(raw: List<OuraLivePoint>): List<OuraLivePoint> {
    val window = java.util.ArrayDeque<OuraLivePoint>()
    var sum = 0.0
    return raw.map { point ->
        while (window.isNotEmpty() && window.first.elapsedMs < point.elapsedMs - 250) sum -= window.removeFirst().value
        window.addLast(point); sum += point.value
        point.copy(value = sum / window.size)
    }
}

/** Presentation bounds follow the same retained sample window as the source owner. */
internal fun ouraLiveAvailableFrom(series: List<List<OuraLivePoint>>, end: Long): Long {
    val timedFloor = end - OuraLiveSession.RETENTION_MS
    if (series.none { it.size >= OuraLiveSession.MAX_RETAINED_SAMPLES }) return timedFloor
    val oldestSelected = series.asSequence().flatten().minOfOrNull { it.elapsedMs } ?: timedFloor
    return maxOf(timedFloor, oldestSelected)
}
