package com.noop.ble

import com.noop.oura.OuraCommands
import org.junit.Assert.*
import org.junit.Test

class OuraLiveWorkloadTest {
    @Test fun burstSelectionChangesApplyOnlyFinalDesiredWork() {
        val work = OuraLiveWorkload()
        work.start(OuraLiveSelection(hr = true))
        repeat(200) {
            work.update(OuraLiveSelection(motion = true))
            work.update(OuraLiveSelection(true, true))
            work.start(OuraLiveSelection(hr = true))
        }
        val change = work.drain()!!
        assertNull(change.before)
        assertEquals(OuraLiveSelection(hr = true), change.after)
        assertTrue(change.newSession)
        assertNull(work.drain())
        work.start(OuraLiveSelection(hr = true))
        assertNull(work.drain())
    }
    @Test fun stopThenUpdateCannotRestartAndOldRevisionCannotOwnCurrentSession() {
        val work = OuraLiveWorkload()
        work.start(OuraLiveSelection(hr = true))
        val original = work.drain()!!
        work.stop()
        work.update(OuraLiveSelection(true,true))
        assertNull(work.drain()!!.after)
        work.start(OuraLiveSelection(motion = true))
        val current = work.drain()!!
        assertTrue(current.generation > original.generation)
        assertTrue(current.newSession)
    }
    @Test fun rapidWorkCannotBacklogOldEnablesAndShutdownPrecedesFinalStart() {
        val sent = mutableListOf<String>()
        val queue = OuraWriteQueue(submit = { sent.add(it.label); true })
        queue.enqueue(OuraCommands.getEvents(0, 255))
        repeat(200) {
            queue.replaceLiveCommands(listOf(OuraCommands.liveHRDisable(), OuraCommands.liveHRUnsubscribe(),
                OuraCommands.liveMotionStop(), OuraCommands.liveHREnable(), OuraCommands.liveHRSubscribe()))
            queue.replaceLiveCommands(listOf(OuraCommands.liveHRDisable(), OuraCommands.liveHRUnsubscribe(),
                OuraCommands.liveMotionStop(), OuraCommands.liveMotionStart()))
        }
        repeat(8) { queue.complete(true) }
        assertEquals(listOf("get_events", "dhr_disable", "dhr_unsubscribe", "acm_stop", "acm_start"), sent)
    }
    @Test fun pendingOrStoppedGenerationCannotDeliverSamples() {
        val work = OuraLiveWorkload()
        work.start(OuraLiveSelection(true,true))
        assertFalse(work.acceptsSamples(work.generation))
        work.drain()
        val old = work.generation
        assertTrue(work.acceptsSamples(old))
        work.update(OuraLiveSelection(motion = true))
        assertFalse(work.acceptsSamples(old))
        work.drain()
        assertTrue(work.acceptsSamples(old))
        work.stop()
        assertFalse(work.acceptsSamples(work.generation))
        work.start(OuraLiveSelection(hr = true))
        work.drain()
        assertFalse(work.acceptsSamples(old))
    }

    @Test fun stopStartCannotRenewMotionUntilShutdownActuallyCompletes() {
        val work = OuraLiveWorkload()
        assertEquals(60_000L, work.armMotionDeadline(0))
        work.stop()
        work.start(OuraLiveSelection(motion = true))
        assertEquals(60_000L, work.armMotionDeadline(10_000))
        work.motionShutdownCompleted(sessionActive = true)
        assertEquals(60_000L, work.armMotionDeadline(20_000))
        work.motionShutdownCompleted(sessionActive = false)
        assertEquals(90_000L, work.armMotionDeadline(30_000))
    }

}
