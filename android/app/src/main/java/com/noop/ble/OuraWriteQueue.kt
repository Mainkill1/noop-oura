package com.noop.ble

import com.noop.oura.OuraCommand

/** One outstanding Android write per connection. An uncertain write is never replayed. */
internal class OuraWriteQueue(
    private val submit: (OuraCommand) -> Boolean,
    private val onStarted: (OuraCommand) -> Unit = {},
    private val onFinished: () -> Unit = {},
    private val onCompleted: (OuraCommand) -> Unit = {},
    private val onFailure: (OuraCommand, Failure) -> Unit = { _, _ -> },
) {
    enum class Failure { REJECTED, COMPLETION_FAILED, TIMED_OUT }
    private val pending = java.util.ArrayDeque<OuraCommand>()
    private var inFlight: OuraCommand? = null
    private var closed = false
    private var finishing = false
    private var onDrained: (() -> Unit)? = null

    @Synchronized fun enqueue(command: OuraCommand) {
        if (closed || finishing) return
        pending.addLast(command)
        pump()
    }

    @Synchronized fun replaceLiveCommands(commands: List<OuraCommand>) {
        if (closed || finishing) return
        pending.removeAll { it.label in LIVE_LABELS }
        pending.addAll(commands)
        pump()
    }

    @Synchronized fun complete(success: Boolean) {
        val command = inFlight ?: return
        if (!success) { fail(command, Failure.COMPLETION_FAILED); return }
        inFlight = null
        onCompleted(command)
        onFinished()
        pump()
    }

    @Synchronized fun timeout(command: OuraCommand) {
        if (inFlight === command) fail(command, Failure.TIMED_OUT)
    }

    @Synchronized fun close() {
        closed = true
        onDrained = null
        pending.clear()
        val active = inFlight != null
        inFlight = null
        if (active) onFinished()
    }

    /** Keep only an already accepted write and the shutdown pair; reject new normal work. */
    @Synchronized fun finishWith(commands: List<OuraCommand>, drained: () -> Unit) {
        if (closed) { drained(); return }
        finishing = true
        pending.clear()
        pending.addAll(commands)
        onDrained = drained
        pump()
    }

    private fun pump() {
        if (closed || inFlight != null) return
        val command = pending.pollFirst()
        if (command == null) {
            val drained = onDrained
            if (drained != null) { close(); drained() }
            return
        }
        inFlight = command
        onStarted(command)
        val accepted = submit(command)
        // A callback may run before submit returns; never fail a later command in that case.
        if (!accepted && inFlight === command) fail(command, Failure.REJECTED)
    }

    private fun fail(command: OuraCommand, reason: Failure) {
        close()
        onFailure(command, reason)
    }
    companion object {
        private val LIVE_LABELS = setOf("dhr_enable", "dhr_subscribe", "dhr_disable", "dhr_unsubscribe", "acm_start", "acm_stop")
    }

}
