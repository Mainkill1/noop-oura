package com.noop.ble

import com.noop.oura.OuraCommand
import com.noop.oura.OuraCommands

/** One outstanding Android write per connection. An uncertain write is never replayed. */
internal class OuraWriteQueue(
    private val submit: (OuraCommand) -> Boolean,
    private val onStarted: (OuraCommand) -> Unit = {},
    private val onFinished: () -> Unit = {},
    private val onCompleted: (OuraCommand) -> Unit = {},
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onFailure: (OuraCommand, Failure) -> Unit = { _, _ -> },
    private val onPolicyBlocked: (OuraCommand) -> Unit = {},
) {
    enum class Failure { REJECTED, COMPLETION_FAILED, TIMED_OUT }
    private val pending = java.util.ArrayDeque<OuraCommand>()
    private var inFlight: OuraCommand? = null
    private var closed = false
    private var finishing = false
    private var onDrained: (() -> Unit)? = null
    private var onLiveStopped: (() -> Unit)? = null
    private var liveSelection: OuraLiveSelection? = null
    // Tracks this queue's live request, not the ring's background feature mode.
    private var hrModeRequested = false
    private var hrSubscriptionMayBeEnabled = false
    private var motionMayBeEnabled = false
    private var motionLeaseStartedMs: Long? = null
    // Restore automatic measurement on exit; never disable normal background HR.
    private val backgroundHrOff = OuraCommands.liveHRDisable().bytes
    private fun blocksBackgroundHr(command: OuraCommand) = command.bytes.contentEquals(backgroundHrOff)

    /** One renewal at most; selection reconciliation cancels a queued renewal before OFF. */
    @Synchronized fun renewMotionLease() {
        val started = motionLeaseStartedMs ?: return
        if (closed || finishing || liveSelection?.motion != true || !motionMayBeEnabled ||
            elapsedMs() - started < 50_000L || inFlight?.label == "acm_start" || pending.any { it.label == "acm_start" }) return
        pending.addLast(OuraCommands.liveMotionStart())
        pump()
    }

    @Synchronized fun enqueue(command: OuraCommand) {
        if (closed || finishing) return
        if (blocksBackgroundHr(command)) { onPolicyBlocked(command); return }
        if (obsoleteEnable(command)) return
        pending.addLast(command)
        pump()
    }

    // Raw reads may cancel only work the stack has not accepted. Never replay an uncertain write.
    @Synchronized fun cancelPending(command: OuraCommand): Boolean = pending.removeAll { it === command }
    val idle: Boolean @Synchronized get() = !closed && !finishing && inFlight == null && pending.isEmpty()

    /** Invalidate removed starts immediately; only new starts wait for the source's settling budget. */
    @Synchronized fun reconcileLive(selection: OuraLiveSelection, allowStarts: Boolean) {
        if (closed || finishing) return
        liveSelection = selection
        pending.removeAll(::obsoleteEnable)
        // Project only writes that will precede appended work. OFFs already accepted/queued stay ordered.
        var mode = hrModeRequested
        var subscription = hrSubscriptionMayBeEnabled
        var motion = motionMayBeEnabled
        fun project(command: OuraCommand) {
            if (blocksBackgroundHr(command)) return
            when (command.label) {
                "dhr_enable" -> mode = true
                "dhr_subscribe" -> subscription = true
                "dhr_disable" -> mode = false
                "dhr_automatic" -> mode = false
                "dhr_unsubscribe" -> { mode = false; subscription = false }
                "acm_start" -> motion = true
                "acm_stop" -> motion = false
            }
        }
        inFlight?.let(::project)
        pending.forEach(::project)
        if (!selection.hr && (mode || subscription)) {
            pending.add(OuraCommands.liveHRRestoreAutomatic())
            pending.add(OuraCommands.liveHRUnsubscribe())
        }
        if (!selection.motion && motion) pending.add(OuraCommands.liveMotionStop())
        if (allowStarts) {
            if (selection.hr && !mode) pending.add(OuraCommands.liveHREnable())
            if (selection.hr && !subscription) pending.add(OuraCommands.liveHRSubscribe())
            if (selection.motion && !motion) pending.add(OuraCommands.liveMotionStart())
        }
        pump()
    }

    /** Completion barrier: motion stop, automatic HR restoration and unsubscribe must complete. */
    @Synchronized fun whenLiveStopped(action: () -> Unit) {
        if (closed || finishing) return
        onLiveStopped = action
        notifyLiveStopped()
    }
    @Synchronized fun cancelLiveStoppedCallback() { onLiveStopped = null }

    @Synchronized fun complete(success: Boolean) {
        val command = inFlight ?: return
        if (!success) { fail(command, Failure.COMPLETION_FAILED); return }
        inFlight = null
        when (command.label) {
            "dhr_disable" -> hrModeRequested = false
            "dhr_automatic" -> hrModeRequested = false
            "dhr_unsubscribe" -> { hrModeRequested = false; hrSubscriptionMayBeEnabled = false }
            "acm_stop" -> { motionMayBeEnabled = false; motionLeaseStartedMs = null }
        }
        onCompleted(command)
        onFinished()
        pump()
        notifyLiveStopped()
    }

    @Synchronized fun timeout(command: OuraCommand) {
        if (inFlight === command) fail(command, Failure.TIMED_OUT)
    }

    @Synchronized fun close() {
        closed = true
        onDrained = null
        onLiveStopped = null
        pending.clear()
        val active = inFlight != null
        inFlight = null
        if (active) onFinished()
    }

    /** Keep only an already accepted write and shutdown commands; reject new normal work. */
    @Synchronized fun finishWith(commands: List<OuraCommand>, drained: () -> Unit) {
        if (closed) { drained(); return }
        finishing = true
        onLiveStopped = null
        pending.clear()
        commands.forEach { command ->
            if (blocksBackgroundHr(command)) onPolicyBlocked(command) else pending.addLast(command)
        }
        onDrained = drained
        pump()
    }

    private fun pump() {
        if (closed || inFlight != null) return
        var command = pending.pollFirst()
        while (command != null) {
            if (blocksBackgroundHr(command)) onPolicyBlocked(command)
            else if (!obsoleteEnable(command)) break
            command = pending.pollFirst()
        }
        if (command == null) {
            notifyLiveStopped()
            val drained = onDrained
            if (drained != null) { close(); drained() }
            return
        }
        inFlight = command
        when (command.label) {
            "dhr_enable" -> hrModeRequested = true
            "dhr_subscribe" -> hrSubscriptionMayBeEnabled = true
            "acm_start" -> { motionMayBeEnabled = true; motionLeaseStartedMs = elapsedMs() }
        }
        onStarted(command)
        val accepted = submit(command)
        // A callback may run before submit returns; never fail a later command in that case.
        if (!accepted && inFlight === command) fail(command, Failure.REJECTED)
    }

    private fun obsoleteEnable(command: OuraCommand): Boolean {
        val desired = liveSelection ?: return false
        return when (command.label) {
            "dhr_enable", "dhr_subscribe" -> !desired.hr
            "acm_start" -> !desired.motion
            else -> false
        }
    }
    private fun notifyLiveStopped() {
        if (closed || finishing || hrModeRequested || hrSubscriptionMayBeEnabled || motionMayBeEnabled) return
        if (inFlight?.label in LIVE_LABELS || pending.any { it.label in LIVE_LABELS }) return
        val callback = onLiveStopped ?: return
        onLiveStopped = null
        callback()
    }

    private fun fail(command: OuraCommand, reason: Failure) {
        close()
        onFailure(command, reason)
    }
    companion object {
        private val LIVE_LABELS = setOf("dhr_enable", "dhr_subscribe", "dhr_disable", "dhr_automatic", "dhr_unsubscribe", "acm_start", "acm_stop")
    }

}
