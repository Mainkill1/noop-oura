package com.noop.ble

import com.noop.oura.*
import org.junit.Assert.*
import org.junit.Test

class OuraRawPollTest {
    private class Harness {
        var now = 0L
        val queued = mutableListOf<OuraCommand>()
        val poll = OuraRawPoll(777, { now }, { queued.add(it) }, { queued.remove(it) })
        fun select(key: String = "battery", interval: Long = 2000, token: Long = poll.snapshot.generation) =
            poll.intent(token, key, interval, true)
        fun tick(ready: Boolean = true) = poll.tick(ready)
        fun response(op: Int = 0x0d, body: List<Int> = listOf(90, 0, 0)) {
            poll.response(OuraOuterFrame(op, body.toIntArray()))
        }
    }
    @Test fun coalescesQueuedWorkAndNeverOverlapsOrCatchesUp() {
        val h = Harness(); h.select(); h.tick()
        val obsolete = h.queued.single()
        h.select("status:04", 250); h.tick()
        assertEquals(1, h.queued.size); assertNotSame(obsolete, h.queued.single())
        val command = h.queued.single(); h.poll.started(command); h.queued.clear()
        repeat(20) { h.now += 1000; h.tick() }
        assertTrue(h.queued.isEmpty())
        assertEquals(OuraRawPollPhase.STALLED, h.poll.snapshot.phase)
    }
    @Test fun earlyReplySurvivesWriteCompletionAndPacesFromBothFacts() {
        val h = Harness(); h.select(); h.tick(); val c = h.queued.single()
        h.now = 20; h.poll.started(c)
        h.now = 35; h.response()
        assertEquals(15L, h.poll.snapshot.responseLatencyMs)
        h.now = 50; h.poll.completed(c)
        assertEquals(30L, h.poll.snapshot.writeCompletionMs)
        assertEquals(15L, h.poll.snapshot.responseLatencyMs)
        assertEquals(20L, h.poll.snapshot.queueLatencyMs)
        h.now = 2049; h.tick(); assertEquals(1, h.queued.size)
        h.now = 2050; h.tick(); assertEquals(2, h.queued.size)
        h.now = 999999; h.tick(); assertEquals(2, h.queued.size)
    }
    @Test fun matchesFeatureAndRequiresStructurallyValidReplies() {
        val h = Harness(); h.select("status:04"); h.tick(); val c = h.queued.single(); h.poll.started(c)
        h.response(0x2f, listOf(0x21, 2, 1, 2, 3, 4))
        h.response(0x2f, listOf(0x21, 4, 1))
        assertNull(h.poll.snapshot.responseLatencyMs)
        h.now = 91; h.response(0x2f, listOf(0x21, 4, 0, 0, 0, 0))
        assertEquals(91L, h.poll.snapshot.responseLatencyMs)
    }
    @Test fun staleOwnersAndBackgroundCannotRestartOrReplayUncertainRead() {
        val h = Harness(); val owner = h.poll.snapshot.generation
        h.select(); h.tick(); val c = h.queued.single(); h.poll.started(c)
        h.poll.intent(owner, null, 2000, false)
        h.select(token = owner); h.tick(); assertEquals(OuraRawPollPhase.STOPPED, h.poll.snapshot.phase)
        h.select(); h.tick(); assertEquals(1, h.queued.size)
        h.now = 9000; h.tick(); assertEquals(OuraRawPollPhase.STALLED, h.poll.snapshot.phase)
        h.poll.reset(); h.poll.completed(c); h.response(); assertNull(h.poll.snapshot.responseLatencyMs)
        h.select(); h.tick(false); assertEquals(OuraRawPollPhase.WAITING, h.poll.snapshot.phase)
    }
    @Test fun pushAndHistoryNeverInventAPoll() {
        val h = Harness()
        for (key in listOf("hr", "motion", "event:8b", "event:90")) { h.select(key); h.tick() }
        assertTrue(h.queued.isEmpty())
    }
    @Test fun acceptedOldOwnerReplyOnlyReleasesBarrierAndNeverPublishesAsNewOwnerTiming() {
        val h = Harness(); val oldOwner = h.poll.snapshot.generation
        h.select(); h.tick(); val c = h.queued.single(); h.poll.started(c)
        h.poll.intent(oldOwner, null, 2000, false)
        h.select(); h.now = 90; h.response(); h.poll.completed(c)
        assertNull(h.poll.snapshot.responseLatencyMs)
        assertNull(h.poll.snapshot.writeCompletionMs)
        h.now = 2089; h.tick(); assertEquals(1, h.queued.size)
        h.now = 2090; h.tick(); assertEquals(2, h.queued.size)
    }
    @Test fun sourceIncarnationTokensAndOldGATTCallbacksAreOpaqueAndIgnored() {
        val h = Harness()
        h.select(token = OuraRawPoll(778, { 0 }, {}, { true }).snapshot.generation)
        h.tick(); assertTrue(h.queued.isEmpty())
        h.select(); h.tick(); val old = h.queued.single()
        h.poll.reset(); h.poll.started(old); h.poll.completed(old)
        assertNull(h.poll.snapshot.queueLatencyMs)
    }
    @Test fun callbackReceiptTimestampExcludesHandlerQueueDelay() {
        val h = Harness(); h.select(); h.tick(); val c = h.queued.single()
        h.now = 10; h.poll.started(c)
        h.now = 80; h.poll.response(OuraOuterFrame(0x0d, intArrayOf(50, 0, 0)), 25)
        assertEquals(15L, h.poll.snapshot.responseLatencyMs)
    }

    @Test fun realQueueSubmissionPreservesEarlyResponseAndDoesNotDelayMotionOffWithRawBacklog() {
        var now = 0L
        lateinit var poll: OuraRawPoll
        lateinit var queue: OuraWriteQueue
        val sent = mutableListOf<String>()
        queue = OuraWriteQueue(submit = { command ->
            sent.add(command.label)
            if (command.label == OuraCommands.getBattery().label) {
                now += 15
                poll.response(OuraOuterFrame(0x0d, intArrayOf(90, 0, 0)))
            }
            true
        }, onStarted = { poll.started(it) }, onCompleted = { poll.completed(it) })
        poll = OuraRawPoll(3, { now }, { queue.enqueue(it) }, { queue.cancelPending(it) })
        queue.reconcileLive(OuraLiveSelection(motion = true), true); queue.complete(true)
        poll.intent(poll.snapshot.generation, "battery", 250, true); poll.tick(queue.idle)
        repeat(100) { poll.intent(poll.snapshot.generation, "status:04", 500, true) }
        queue.reconcileLive(OuraLiveSelection(), false)
        now += 5; queue.complete(true)
        assertEquals(listOf("acm_start", OuraCommands.getBattery().label, "acm_stop"), sent)
        assertEquals(15L, poll.snapshot.responseLatencyMs)
        assertEquals(20L, poll.snapshot.writeCompletionMs)
        queue.complete(true)
        assertTrue(queue.idle)
    }

    @Test fun callbackCompletionTimestampExcludesHandlerQueueDelay() {
        val h = Harness(); h.select(); h.tick(); val c = h.queued.single()
        h.now = 10; h.poll.started(c)
        h.now = 80; h.poll.completed(c, 30)
        assertEquals(20L, h.poll.snapshot.writeCompletionMs)
    }

    @Test fun lateReplyCannotBypassDeadlineWhenTimerDispatchWasDelayed() {
        val h = Harness(); h.select(); h.tick(); val c = h.queued.single(); h.poll.started(c); h.poll.completed(c)
        h.now = 9000; h.response() // no timer tick ran during a blocked handler
        h.now = 12000; h.tick()
        assertEquals(OuraRawPollPhase.STALLED, h.poll.snapshot.phase)
        assertEquals(1, h.queued.size)
    }

    @Test fun intervalIncreaseAfterCompletionWaitsFromTheCompletionBoundary() {
        val h = Harness(); h.select(); h.tick(); val c = h.queued.single(); h.poll.started(c)
        h.now = 100; h.response(); h.poll.completed(c)
        h.now = 110; h.select(interval = 30000); h.tick()
        assertEquals(1, h.queued.size)
        assertEquals(OuraRawPollPhase.PACING, h.poll.snapshot.phase)
        h.now = 30099; h.tick(); assertEquals(1, h.queued.size)
        h.now = 30100; h.tick(); assertEquals(2, h.queued.size)
        h.now = 1000000; repeat(20) { h.tick() }
        assertEquals(2, h.queued.size) // a delayed timer may issue one request, never a catch-up burst
    }

    @Test fun intervalDecreaseUsesTheNewIntervalWithoutBypassingTheMinimumCadence() {
        val h = Harness(); h.select(interval = 30000); h.tick(); val c = h.queued.single(); h.poll.started(c)
        h.now = 100; h.response(); h.poll.completed(c)
        h.now = 110; h.select(interval = 250); h.tick()
        assertEquals(1, h.queued.size)
        h.now = 349; h.tick(); assertEquals(1, h.queued.size)
        h.now = 350; h.tick(); assertEquals(2, h.queued.size)
    }

    @Test fun rapidChannelChangesAndObservingHistoryDoNotEraseCompletionPacing() {
        val h = Harness(); h.select(interval = 500); h.tick(); val c = h.queued.single(); h.poll.started(c)
        h.now = 100; h.response(); h.poll.completed(c)
        for (key in listOf("status:04", "event:8b", "hr", "status:02", "status:0b", "battery")) {
            h.now += 1; h.select(key, 500); h.tick()
            assertEquals("channel=$key", 1, h.queued.size)
        }
        h.now = 599; h.tick(); assertEquals(1, h.queued.size)
        h.now = 600; h.tick(); assertEquals(2, h.queued.size)
    }

    @Test fun completedRequestHideAndReentryRetainPacingUntilTheLatestChosenDeadline() {
        val h = Harness(); val owner = h.poll.snapshot.generation
        h.select(); h.tick(); val c = h.queued.single(); h.poll.started(c)
        h.now = 100; h.response(); h.poll.completed(c)
        h.now = 110; h.poll.intent(owner, null, 2000, false)
        h.now = 120; h.select("status:04", 1000); h.tick()
        assertEquals(1, h.queued.size)
        h.now = 1099; h.tick(); assertEquals(1, h.queued.size)
        h.now = 1100; h.tick(); assertEquals(2, h.queued.size)
    }

    @Test fun newConnectionResetReleasesOnlyThePreviousConnectionsPacingBoundary() {
        val h = Harness(); h.select(interval = 30000); h.tick(); val c = h.queued.single(); h.poll.started(c)
        h.now = 100; h.response(); h.poll.completed(c)
        h.now = 110; h.poll.reset(); h.select(interval = 250); h.tick()
        assertEquals(2, h.queued.size)
        h.poll.completed(c) // a stale callback cannot establish pacing in the new connection
        h.poll.started(h.queued.last()); h.now = 120; h.response(); h.poll.completed(h.queued.last())
        h.now = 369; h.tick(); assertEquals(2, h.queued.size)
        h.now = 370; h.tick(); assertEquals(3, h.queued.size)
    }

}
