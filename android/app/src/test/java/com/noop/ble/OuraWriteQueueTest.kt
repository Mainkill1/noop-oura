package com.noop.ble

import com.noop.oura.OuraCommand
import org.junit.Assert.*
import org.junit.Test

/** Android accepts only one outstanding characteristic write, including WRITE_TYPE_NO_RESPONSE. */
class OuraWriteQueueTest {
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
}
