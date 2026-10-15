package com.noop.ble

import com.noop.oura.OuraLiveMotionPacket

data class OuraLiveSelection(val hr: Boolean = false, val motion: Boolean = false)
enum class OuraHistorySyncPhase { IDLE, SYNCING, PAUSED_FOR_LIVE }
enum class OuraLiveStreamPhase { IDLE, WAITING, RECEIVING, STALE, UNAVAILABLE }
data class OuraLiveHrSample(val receivedAtElapsedMs: Long, val bpm: Int, val ibiMs: Int?)
data class OuraLiveMotionSample(val receivedAtElapsedMs: Long, val sequence: Int, val sampleIndex: Int, val x: Int, val y: Int, val z: Int, val metadataByte: Int)
data class OuraLiveStream<T>(val samples: List<T> = emptyList(), val phase: OuraLiveStreamPhase = OuraLiveStreamPhase.IDLE,
    val lastReceiptElapsedMs: Long? = null, val observedRateHz: Double? = null)
data class OuraLiveSessionSnapshot(val active: Boolean = false, val generation: Long = 0, val selection: OuraLiveSelection = OuraLiveSelection(),
    val startedAtElapsedMs: Long? = null, val sourceDeviceId: String? = null,
    val motionDeadlineElapsedMs: Long? = null, val hr: OuraLiveStream<OuraLiveHrSample> = OuraLiveStream(),
    val motion: OuraLiveStream<OuraLiveMotionSample> = OuraLiveStream(),
    val sourceIncarnation: Long = 0)

/** Notification-only, bounded session memory; historical events have no entry point here. */
internal class OuraLiveSession {
    var snapshot = OuraLiveSessionSnapshot()
        private set
    private val hr = java.util.ArrayDeque<OuraLiveHrSample>()
    private val motion = java.util.ArrayDeque<OuraLiveMotionSample>()
    private var lastHrReceipt: Long? = null
    private var lastMotionReceipt: Long? = null
    private var hrUnavailable = false
    private var motionUnavailable = false

    fun start(selection: OuraLiveSelection, now: Long) {
        hr.clear(); motion.clear()
        lastHrReceipt = null; lastMotionReceipt = null
        hrUnavailable = false; motionUnavailable = false
        snapshot = OuraLiveSessionSnapshot(active = selection.hr || selection.motion, selection = selection, startedAtElapsedMs = now)
        tick(now)
    }
    fun update(selection: OuraLiveSelection, now: Long) {
        if (!snapshot.active) return
        snapshot = snapshot.copy(selection = selection)
        tick(now)
    }
    fun stop(clearSamples: Boolean = false) {
        if (clearSamples) {
            hr.clear(); motion.clear()
            lastHrReceipt = null; lastMotionReceipt = null
            snapshot = OuraLiveSessionSnapshot(selection = snapshot.selection)
        } else snapshot = snapshot.copy(active = false, hr = snapshot.hr.copy(phase = OuraLiveStreamPhase.IDLE),
            motion = snapshot.motion.copy(phase = OuraLiveStreamPhase.IDLE))
    }
    fun unavailable(hr: Boolean = false, motion: Boolean = false, now: Long) {
        hrUnavailable = hrUnavailable || hr
        motionUnavailable = motionUnavailable || motion
        tick(now)
    }
    fun tick(now: Long) {
        prune(hr, now) { it.receivedAtElapsedMs }
        prune(motion, now) { it.receivedAtElapsedMs }
        snapshot = snapshot.copy(
            hr = stream(hr.toList(), snapshot.selection.hr, hrUnavailable, lastHrReceipt, now, 5_000) { it.receivedAtElapsedMs },
            motion = stream(motion.toList(), snapshot.selection.motion, motionUnavailable, lastMotionReceipt, now, 1_500) { it.receivedAtElapsedMs })
    }
    fun noteHr(bpm: Int, ibiMs: Int?, now: Long): Boolean {
        if (!snapshot.active || !snapshot.selection.hr || hrUnavailable || now < (snapshot.startedAtElapsedMs ?: now)) return false
        lastHrReceipt = now
        hr.addLast(OuraLiveHrSample(now, bpm, ibiMs))
        hrUnavailable = false
        tick(now)
        return true
    }
    fun noteMotion(packet: OuraLiveMotionPacket, now: Long): Boolean {
        if (!snapshot.active || !snapshot.selection.motion || motionUnavailable || now < (snapshot.startedAtElapsedMs ?: now)) return false
        lastMotionReceipt = now
        packet.samples.forEachIndexed { index, v ->
            motion.addLast(OuraLiveMotionSample(now, packet.sequence, index, v.x, v.y, v.z, packet.metadataByte))
        }
        motionUnavailable = false
        tick(now)
        return true
    }
    private fun <T> prune(samples: java.util.ArrayDeque<T>, now: Long, receipt: (T) -> Long) {
        while (samples.isNotEmpty() && (samples.size > 6_000 || receipt(samples.first) < now - 60_000)) samples.removeFirst()
    }
    private fun <T> stream(samples: List<T>, selected: Boolean, unavailable: Boolean, last: Long?, now: Long,
                           staleAfter: Long, receipt: (T) -> Long): OuraLiveStream<T> {
        val phase = when {
            !snapshot.active || !selected -> OuraLiveStreamPhase.IDLE
            last != null && now - last <= staleAfter -> OuraLiveStreamPhase.RECEIVING
            unavailable -> OuraLiveStreamPhase.UNAVAILABLE
            last != null -> OuraLiveStreamPhase.STALE
            else -> OuraLiveStreamPhase.WAITING
        }
        val recent = samples.filter { receipt(it) >= now - 5_000 }
        val firstTime = recent.firstOrNull()?.let(receipt)
        val rate = if (firstTime != null && last != null && last > firstTime)
            recent.count { receipt(it) > firstTime } * 1_000.0 / (last - firstTime) else null
        return OuraLiveStream(samples, phase, last, rate)
    }
}
