package com.noop.ble

/** One desired revision, reconciled by the source's single worker. UI calls never write the ring. */
internal class OuraLiveWorkload {
    data class Change(val before: OuraLiveSelection?, val after: OuraLiveSelection?, val generation: Long, val newSession: Boolean)
    // Opaque token: incarnation namespace survives A -> B -> A recreation within the process.
    @Volatile var generation = nextIncarnation.incrementAndGet() shl 32
        private set
    private var desired: OuraLiveSelection? = null
    val hasDemand: Boolean get() = desired != null
    private var applied: OuraLiveSelection? = null
    private var appliedGeneration = generation
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
    fun acceptsSamples(generation: Long, motion: Boolean = false): Boolean {
        if (generation != this.generation || appliedGeneration != generation) return false
        return if (motion) desired?.motion == true && applied?.motion == true
        else desired?.hr == true && applied?.hr == true
    }
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
    private companion object {
        val nextIncarnation = java.util.concurrent.atomic.AtomicLong()
    }

}
