package com.noop.ble

import com.noop.oura.OuraCommand
import com.noop.oura.OuraCommands
import org.junit.Assert.*
import org.junit.Test

/** Android accepts only one outstanding characteristic write, including WRITE_TYPE_NO_RESPONSE. */
class OuraWriteQueueTest {
    @Test fun delayedRenewalIsSingleAndCancelledBeforeStopBarrier() {
        var now = 0L
        val sent = mutableListOf<String>()
        val queue = OuraWriteQueue(submit = { sent.add(it.label); true }, elapsedMs = { now })
        queue.reconcileLive(OuraLiveSelection(motion = true), true)
        now = 70_000
        repeat(200) { queue.renewMotionLease() }
        assertEquals(listOf("acm_start"), sent) // initial write still outstanding
        queue.complete(true)
        queue.enqueue(command("get_battery"))
        repeat(200) { queue.renewMotionLease() }
        queue.reconcileLive(OuraLiveSelection(), false)
        var stopped = false
        queue.whenLiveStopped { stopped = true }
        queue.complete(true)
        assertEquals(listOf("acm_start", "get_battery", "acm_stop"), sent)
        assertFalse(stopped)
        queue.complete(true)
        assertTrue(stopped)
        now += 120_000
        queue.renewMotionLease()
        assertEquals(1, sent.count { it == "acm_start" })
    }

    private fun command(label: String) = OuraCommand(label, intArrayOf(1))

    @Test fun startupBurstDeliversNonceHistoryAndStatusReadsWithoutBusyDrops() {
        var busy = false
        val submitted = mutableListOf<String>()
        val failures = mutableListOf<OuraWriteQueue.Failure>()
        val queue = OuraWriteQueue(submit = {
            if (busy) false else { busy = true; submitted += it.label; true }
        }, onFailure = { _, reason -> failures += reason })
        val labels = listOf("notify_all", "get_nonce", "sync_time", "flush_buffer", "get_events", "get_battery", "get_serial")
        labels.forEach { queue.enqueue(command(it)) }
        assertEquals(listOf("notify_all"), submitted)
        repeat(labels.size) { busy = false; queue.complete(true) }
        assertEquals(labels, submitted)
        assertTrue(failures.isEmpty())
    }

    @Test fun commandEnqueuedByNotificationWaitsForOutstandingWriteCallback() {
        val sent = mutableListOf<String>()
        val queue = OuraWriteQueue(submit = { sent += it.label; true })
        queue.enqueue(command("submit_proof"))
        queue.enqueue(command("dhr_read"))
        assertEquals(listOf("submit_proof"), sent)
        queue.complete(true)
        assertEquals(listOf("submit_proof", "dhr_read"), sent)
    }

    @Test fun rejectedWriteStopsSessionWithoutSendingLaterCommands() {
        val sent = mutableListOf<String>()
        val failures = mutableListOf<Pair<String, OuraWriteQueue.Failure>>()
        val queue = OuraWriteQueue(submit = { sent += it.label; false },
            onFailure = { command, reason -> failures += command.label to reason })
        queue.enqueue(command("get_nonce")); queue.enqueue(command("get_events"))
        assertEquals(listOf("get_nonce"), sent)
        assertEquals(listOf("get_nonce" to OuraWriteQueue.Failure.REJECTED), failures)
    }

    @Test fun failedCompletionDoesNotSendNextCommandOrRetryKeyInstall() {
        val sent = mutableListOf<String>()
        val failures = mutableListOf<OuraWriteQueue.Failure>()
        val queue = OuraWriteQueue(submit = { sent += it.label; true }, onFailure = { _, reason -> failures += reason })
        queue.enqueue(command("DANGEROUS_install_key")); queue.enqueue(command("get_nonce"))
        queue.complete(false); queue.enqueue(command("get_battery"))
        assertEquals(listOf("DANGEROUS_install_key"), sent)
        assertEquals(listOf(OuraWriteQueue.Failure.COMPLETION_FAILED), failures)
    }

    @Test fun timeoutNeverReplaysAnAcceptedKeyInstall() {
        val keyInstall = command("DANGEROUS_install_key")
        val sent = mutableListOf<String>()
        val failures = mutableListOf<OuraWriteQueue.Failure>()
        val queue = OuraWriteQueue(submit = { sent += it.label; true }, onFailure = { _, reason -> failures += reason })
        queue.enqueue(keyInstall); queue.enqueue(command("get_nonce"))
        queue.timeout(keyInstall); queue.complete(true)
        assertEquals(listOf("DANGEROUS_install_key"), sent)
        assertEquals(listOf(OuraWriteQueue.Failure.TIMED_OUT), failures)
    }

    @Test fun obsoleteTimeoutCannotCancelTheNextWrite() {
        val first = command("sync_time"); val second = command("get_events")
        val failures = mutableListOf<OuraWriteQueue.Failure>()
        val queue = OuraWriteQueue(submit = { true }, onFailure = { _, reason -> failures += reason })
        queue.enqueue(first); queue.enqueue(second); queue.complete(true); queue.timeout(first)
        assertTrue(failures.isEmpty())
        queue.timeout(second)
        assertEquals(listOf(OuraWriteQueue.Failure.TIMED_OUT), failures)
    }

    @Test fun closingDropsQueuedCommandsAndLateCompletions() {
        val sent = mutableListOf<String>()
        val queue = OuraWriteQueue(submit = { sent += it.label; true })
        queue.enqueue(command("sync_time")); queue.enqueue(command("get_events"))
        queue.close(); queue.complete(true); queue.enqueue(command("get_battery"))
        assertEquals(listOf("sync_time"), sent)
    }

    @Test fun teardownReplacesBacklogAndWaitsForBothShutdownWrites() {
        val sent = mutableListOf<String>()
        var disconnected = false
        val queue = OuraWriteQueue(submit = { sent += it.label; true })
        queue.enqueue(command("sync_time")); queue.enqueue(command("get_events"))
        queue.finishWith(listOf(command("dhr_disable"), command("dhr_unsubscribe"))) { disconnected = true }
        queue.enqueue(command("get_battery"))
        assertFalse(disconnected)
        queue.complete(true)
        assertEquals(listOf("sync_time", "dhr_disable"), sent)
        queue.complete(true)
        assertEquals(listOf("sync_time", "dhr_disable", "dhr_unsubscribe"), sent)
        assertFalse(disconnected)
        queue.complete(true)
        assertTrue(disconnected)
    }

    @Test fun teardownOfIdleQueueDisconnectsOnlyAfterShutdownCompletion() {
        var disconnected = 0
        val queue = OuraWriteQueue(submit = { true })
        queue.finishWith(listOf(command("dhr_disable"))) { disconnected++ }
        assertEquals(0, disconnected)
        queue.complete(true); queue.complete(true)
        assertEquals(1, disconnected)
    }

    @Test fun completionCanArriveInlineWithoutOverlappingWritesOrLeakingTimeouts() {
        val events = mutableListOf<String>()
        lateinit var queue: OuraWriteQueue
        queue = OuraWriteQueue(submit = { events += "send:${it.label}"; queue.complete(true); true },
            onStarted = { events += "start:${it.label}" }, onFinished = { events += "finish" })
        queue.enqueue(command("notify_all")); queue.enqueue(command("get_nonce"))
        assertEquals(listOf("start:notify_all", "send:notify_all", "finish", "start:get_nonce", "send:get_nonce", "finish"), events)
    }
    @Test fun desiredRemovalInvalidatesPendingStartsBeforeOutstandingCompletion() {
        val sent = mutableListOf<String>()
        val queue = OuraWriteQueue(submit = { sent.add(it.label); true })
        queue.enqueue(command("get_battery"))
        queue.reconcileLive(OuraLiveSelection(motion = true), allowStarts = true)
        queue.reconcileLive(OuraLiveSelection(hr = true), allowStarts = false)
        repeat(8) { queue.complete(true) }
        assertEquals(listOf("get_battery"), sent)
    }
    @Test fun retainedMotionHasNoOffOrRestartWhenHrChanges() {
        val sent = mutableListOf<String>()
        val queue = OuraWriteQueue(submit = { sent.add(it.label); true })
        queue.reconcileLive(OuraLiveSelection(motion = true), allowStarts = true)
        repeat(8) { queue.complete(true) }
        sent.clear()
        queue.reconcileLive(OuraLiveSelection(true,true), allowStarts = true)
        repeat(8) { queue.complete(true) }
        assertEquals(listOf("dhr_enable", "dhr_subscribe"), sent)
        sent.clear()
        queue.reconcileLive(OuraLiveSelection(motion = true), allowStarts = false)
        repeat(8) { queue.complete(true) }
        assertEquals(listOf("dhr_automatic", "dhr_unsubscribe"), sent)
    }
    @Test fun hrRestartWaitsForAutomaticRestorationAndUnsubscribeWithoutDuplicatingTheEnablePair() {
        val sent = mutableListOf<String>()
        val queue = OuraWriteQueue(submit = { sent.add(it.label); true })
        queue.reconcileLive(OuraLiveSelection(hr = true), allowStarts = true)
        // The first enable is accepted; its subscribe is still pending when the page leaves.
        queue.reconcileLive(OuraLiveSelection(), allowStarts = false)
        var resumed = 0
        queue.whenLiveStopped { resumed++ }
        queue.cancelLiveStoppedCallback()
        repeat(200) { queue.reconcileLive(OuraLiveSelection(hr = true), allowStarts = true) }
        assertEquals(listOf("dhr_enable"), sent)
        queue.complete(true)
        assertEquals(listOf("dhr_enable", "dhr_automatic"), sent)
        queue.complete(true)
        assertEquals(listOf("dhr_enable", "dhr_automatic", "dhr_unsubscribe"), sent)
        queue.complete(true)
        assertEquals(listOf("dhr_enable", "dhr_automatic", "dhr_unsubscribe", "dhr_enable"), sent)
        queue.complete(true); queue.complete(true)
        assertEquals(listOf("dhr_enable", "dhr_automatic", "dhr_unsubscribe", "dhr_enable", "dhr_subscribe"), sent)
        assertEquals(0, resumed)
        queue.reconcileLive(OuraLiveSelection(), allowStarts = false)
        queue.whenLiveStopped { resumed++ }
        assertEquals(0, resumed)
        queue.complete(true)
        assertEquals("Restoring the mode does not complete unsubscribe", 0, resumed)
        queue.complete(true)
        assertEquals(1, resumed)
        assertTrue(queue.idle)
    }
    @Test fun automaticQueueBlocksFeatureWideHrOffByPayloadWithoutStrandingShutdown() {
        val sent = mutableListOf<OuraCommand>()
        val started = mutableListOf<String>()
        val queue = OuraWriteQueue(submit = { sent.add(it); true }, onStarted = { started.add(it.label) })
        queue.reconcileLive(OuraLiveSelection(hr = true), allowStarts = true)
        // A producer's label cannot conceal the feature-wide off operation from the worker.
        val off = OuraCommands.liveHRDisable()
        queue.enqueue(OuraCommand("mislabeled_control", off.bytes.copyOf()))
        queue.reconcileLive(OuraLiveSelection(), allowStarts = false)
        var stopped = false
        queue.whenLiveStopped { stopped = true }
        repeat(4) { queue.complete(true) }
        assertTrue(stopped)
        assertEquals(listOf("dhr_enable", "dhr_automatic", "dhr_unsubscribe"), sent.map { it.label })
        assertEquals(sent.map { it.label }, started)
        assertFalse(sent.any { it.bytes.contentEquals(off.bytes) })
        var drained = false
        queue.finishWith(listOf(off)) { drained = true }
        assertTrue("A forbidden teardown operation must not acquire an in-flight write", drained)
        assertEquals(3, started.size)
    }
    @Test fun queuedMutationCannotDisableBackgroundHrOrHideTheRequiredUnsubscribe() {
        val sent = mutableListOf<OuraCommand>()
        val blocked = mutableListOf<String>()
        val queue = OuraWriteQueue(submit = { sent.add(it); true }, onPolicyBlocked = { blocked.add(it.label) })
        queue.reconcileLive(OuraLiveSelection(hr = true), allowStarts = true)
        val queued = OuraCommand("dhr_disable", OuraCommands.liveHREnable().bytes.copyOf())
        queue.enqueue(queued)
        // The producer still owns the mutable bytes while an earlier write is outstanding.
        OuraCommands.liveHRDisable().bytes.copyInto(queued.bytes)
        queue.reconcileLive(OuraLiveSelection(), allowStarts = false)
        var stopped = false
        queue.whenLiveStopped { stopped = true }
        queue.complete(true); queue.complete(true)
        assertEquals(listOf("dhr_enable", "dhr_automatic", "dhr_unsubscribe"), sent.map { it.label })
        assertEquals(listOf("dhr_disable"), blocked)
        assertFalse(stopped)
        queue.complete(true)
        assertTrue(stopped)
        assertTrue(queue.idle)
    }
    @Test fun stoppedBarrierWaitsForActualOffCompletionAndCanBeCancelled() {
        val queue = OuraWriteQueue(submit = { true })
        queue.reconcileLive(OuraLiveSelection(motion = true), allowStarts = true)
        repeat(8) { queue.complete(true) }
        queue.reconcileLive(OuraLiveSelection(), allowStarts = false)
        var resumed = false
        queue.whenLiveStopped { resumed = true }
        assertFalse(resumed)
        queue.complete(true)
        assertTrue(resumed)
        queue.reconcileLive(OuraLiveSelection(motion = true), allowStarts = true)
        repeat(8) { queue.complete(true) }
        queue.reconcileLive(OuraLiveSelection(), allowStarts = false)
        var stale = false
        queue.whenLiveStopped { stale = true }
        queue.cancelLiveStoppedCallback()
        queue.complete(true)
        assertFalse(stale)
    }

    @Test fun obsoleteRawReadCancelsBeforeOffWhileAcceptedReadIsRetained() {
        val sent = mutableListOf<String>()
        val queue = OuraWriteQueue(submit = { sent += it.label; true })
        queue.reconcileLive(OuraLiveSelection(motion = true), true)
        queue.complete(true)
        val accepted = com.noop.oura.OuraCommands.getBattery()
        val obsolete = com.noop.oura.OuraCommands.spo2ReadStatus()
        queue.enqueue(accepted); queue.enqueue(obsolete)
        assertFalse(queue.cancelPending(accepted))
        assertTrue(queue.cancelPending(obsolete))
        queue.reconcileLive(OuraLiveSelection(), false)
        queue.complete(true); queue.complete(true)
        assertEquals(listOf("acm_start", accepted.label, "acm_stop"), sent)
        assertTrue(queue.idle)
    }

}
