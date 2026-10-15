package com.noop.ble

/** One desired revision, reconciled by the source's single worker. UI calls never write the ring. */
internal class OuraLiveWorkload {
    data class Change(val before: OuraLiveSelection?, val after: OuraLiveSelection?, val generation: Long, val newSession: Boolean)
    @Volatile var generation = 0L
        private set
    private var desired: OuraLiveSelection? = null
    private var applied: OuraLiveSelection? = null
    private var appliedGeneration = 0L
    var unfinishedMotionDeadlineElapsedMs: Long? = null
        private set
    fun armMotionDeadline(now: Long): Long {
        val deadline = unfinishedMotionDeadlineElapsedMs ?: (now + 60_000L)
        unfinishedMotionDeadlineElapsedMs = deadline
        return deadline
    }
    fun motionShutdownCompleted(sessionActive: Boolean) {
        if (!sessionActive) unfinishedMotionDeadlineElapsedMs = null
    }
    fun resetMotionBudget() { unfinishedMotionDeadlineElapsedMs = null }
    fun acceptsSamples(generation: Long): Boolean = generation == this.generation &&
        appliedGeneration == generation && desired != null && desired == applied
    fun start(selection: OuraLiveSelection) {
        if (!selection.hr && !selection.motion) return
        if (desired == null) generation += 1
        desired = selection
    }
    fun update(selection: OuraLiveSelection) {
        if (desired == null) return
        if (!selection.hr && !selection.motion) stop() else desired = selection
    }
    fun stop() {
        if (desired != null) { desired = null; generation += 1 }
    }
    fun drain(): Change? {
        if (desired == applied && generation == appliedGeneration) return null
        val change = Change(applied, desired, generation, desired != null && generation != appliedGeneration)
        applied = desired
        appliedGeneration = generation
        return change
    }
}
