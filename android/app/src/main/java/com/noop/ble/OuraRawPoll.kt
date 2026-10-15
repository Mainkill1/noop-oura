package com.noop.ble

import com.noop.oura.*

enum class OuraRawPollPhase { STOPPED, OBSERVING, WAITING, QUEUED, OUTSTANDING, PACING, STALLED }
data class OuraRawPollSnapshot(val generation: Long = 0, val channel: String? = null,
    val intervalMs: Long = 2000, val phase: OuraRawPollPhase = OuraRawPollPhase.STOPPED,
    val timingChannel: String? = null, val queueLatencyMs: Long? = null, val writeCompletionMs: Long? = null,
    val responseLatencyMs: Long? = null)

// Android transport adapter. Command identity correlates GATT callbacks; replies have NO request ID.
// A timeout latches until reset at a new connection. Re-entering a view is not a safe retry boundary.
internal class OuraRawPoll(incarnation: Long, private val now: () -> Long,
    private val enqueue: (OuraCommand) -> Unit, private val cancel: (OuraCommand) -> Boolean) {
    private data class Request(val command: OuraCommand, val key: String, val queued: Long, val generation: Long,
        var started: Long? = null, var completed: Long? = null, var response: Long? = null)
    var snapshot = OuraRawPollSnapshot(generation = incarnation shl 32)
        private set
    private var ownerAlias: Long? = null
    private var active = false
    private var request: Request? = null
    private var stalled = false
    val hasWork: Boolean get() = !stalled && (active || request != null)
    private var nextAt = 0L
    fun intent(expectedGeneration: Long, channel: String?, intervalMs: Long, foreground: Boolean) {
        if (expectedGeneration != snapshot.generation && expectedGeneration != ownerAlias) return
        if (!foreground || channel == null) {
            if (active) { active = false; ownerAlias = null; snapshot = snapshot.copy(generation = snapshot.generation + 1) }
            cancelQueued()
            snapshot = snapshot.copy(channel = null, phase = OuraRawPollPhase.STOPPED)
            return
        }
        if (!active) {
            ownerAlias = expectedGeneration; active = true
            snapshot = snapshot.copy(generation = snapshot.generation + 1, timingChannel = null, queueLatencyMs = null, writeCompletionMs = null, responseLatencyMs = null)
        }
        val interval = intervalMs.takeIf { it in INTERVALS } ?: 2000L
        if (snapshot.channel != channel || snapshot.intervalMs != interval) {
            cancelQueued(); nextAt = now() // source worker coalesces UI revisions before calling tick
        }
        snapshot = snapshot.copy(channel = channel, intervalMs = interval,
            phase = if (stalled) OuraRawPollPhase.STALLED else snapshot.phase)
    }
    // Tests and source use the same tick: no scheduler catch-up, and only idle authenticated work starts.
    fun tick(ready: Boolean) {
        val r = request
        if (r?.started != null && r.response == null && now() - r.started!! >= TIMEOUT) stalled = true
        if (!active) { snapshot = snapshot.copy(phase = OuraRawPollPhase.STOPPED); return }
        val key = snapshot.channel
        val phase = when {
            stalled -> OuraRawPollPhase.STALLED
            command(key) == null -> OuraRawPollPhase.OBSERVING
            r != null -> if (r.started == null) OuraRawPollPhase.QUEUED else OuraRawPollPhase.OUTSTANDING
            !ready -> OuraRawPollPhase.WAITING
            now() < nextAt -> OuraRawPollPhase.PACING
            else -> {
                val cmd = command(key)!!
                request = Request(cmd, key!!, now(), snapshot.generation)
                snapshot = snapshot.copy(timingChannel = key, queueLatencyMs = null, writeCompletionMs = null, responseLatencyMs = null)
                enqueue(cmd)
                if (request == null) OuraRawPollPhase.PACING else if (request?.started != null) OuraRawPollPhase.OUTSTANDING else OuraRawPollPhase.QUEUED
            }
        }
        snapshot = snapshot.copy(phase = phase)
    }
    fun started(command: OuraCommand) {
        val r = request?.takeIf { it.command === command } ?: return
        r.started = now()
        if (visible(r)) snapshot = snapshot.copy(queueLatencyMs = now() - r.queued, phase = OuraRawPollPhase.OUTSTANDING)
    }
    fun completed(command: OuraCommand, completedAtElapsedMs: Long = now()) {
        val r = request?.takeIf { it.command === command } ?: return
        r.completed = completedAtElapsedMs
        if (visible(r)) snapshot = snapshot.copy(writeCompletionMs = r.started?.let { completedAtElapsedMs - it })
        settle(r)
    }
    fun response(frame: OuraOuterFrame, receivedAtElapsedMs: Long = now()) {
        val r = request?.takeIf { it.started != null && it.response == null } ?: return
        val match = if (r.key == "battery") frame.op == 0x0d && OuraDecoders.decodeBattery(frame.body) != null
        else frame.op == 0x2f && frame.body.firstOrNull() == 0x21 &&
            OuraDecoders.decodeFeatureStatus(frame.body.drop(1).toIntArray())?.feature == r.key.substringAfter(':').toInt(16)
        if (!match) return
        if (receivedAtElapsedMs < r.started!!) return
        if (receivedAtElapsedMs - r.started!! >= TIMEOUT) {
            stalled = true
            if (active) snapshot = snapshot.copy(phase = OuraRawPollPhase.STALLED)
        }
        r.response = receivedAtElapsedMs
        if (visible(r)) snapshot = snapshot.copy(responseLatencyMs = receivedAtElapsedMs - r.started!!)
        settle(r)
    }
    private fun visible(r: Request) = active && r.generation == snapshot.generation
    private fun settle(r: Request) {
        if (r.completed == null || r.response == null) return
        request = null
        nextAt = maxOf(r.completed!!, r.response!!) + snapshot.intervalMs
    }
    private fun cancelQueued() {
        val r = request ?: return
        if (r.started == null && cancel(r.command)) request = null
    }
    fun reset() {
        cancelQueued(); request = null; active = false; ownerAlias = null; stalled = false; nextAt = 0
        snapshot = OuraRawPollSnapshot(generation = snapshot.generation + 1)
    }
    private fun command(key: String?): OuraCommand? = when (key) {
        "battery" -> OuraCommands.getBattery()
        "status:02" -> OuraCommands.liveHRReadStatus()
        "status:04" -> OuraCommands.spo2ReadStatus()
        "status:0b" -> OuraCommands.realStepsReadStatus()
        else -> null
    }
    companion object {
        val INTERVALS = listOf(250L, 500L, 1000L, 2000L, 5000L, 10000L, 30000L)
        private const val TIMEOUT = 8000L
    }
}
