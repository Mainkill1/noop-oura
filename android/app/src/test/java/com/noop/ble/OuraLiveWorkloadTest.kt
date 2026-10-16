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
            queue.reconcileLive(OuraLiveSelection(hr = true), allowStarts = true)
            queue.reconcileLive(OuraLiveSelection(motion = true), allowStarts = true)
        }
        repeat(8) { queue.complete(true) }
        assertEquals(listOf("get_events", "acm_start"), sent)
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
        assertTrue(work.acceptsSamples(old, motion = true))
        work.stop()
        assertFalse(work.acceptsSamples(work.generation))
        work.start(OuraLiveSelection(hr = true))
        work.drain()
        assertFalse(work.acceptsSamples(old))
    }

    @Test fun eachSourceIncarnationHasAnIndependentOwnershipNamespace() {
        val firstA = OuraLiveWorkload()
        val otherB = OuraLiveWorkload()
        val secondA = OuraLiveWorkload()
        assertNotEquals(firstA.generation, otherB.generation)
        assertNotEquals(firstA.generation, secondA.generation)
        firstA.start(OuraLiveSelection(hr = true)); firstA.drain()
        secondA.start(OuraLiveSelection(hr = true)); secondA.drain()
        assertFalse(secondA.acceptsSamples(firstA.generation))
    }

    @Test fun retainedChannelKeepsReceivingWhileAnAddedChannelWaitsForWorker() {
        val work = OuraLiveWorkload()
        work.start(OuraLiveSelection(motion = true)); work.drain()
        work.update(OuraLiveSelection(true,true))
        assertTrue(work.acceptsSamples(work.generation, motion = true))
        assertFalse(work.acceptsSamples(work.generation, motion = false))
        work.drain()
        assertTrue(work.acceptsSamples(work.generation, motion = false))
    }


    @Test fun sourceIncarnationIsStableAcrossWorkloadGenerationsAndDistinctOnReplacement() {
        val original = OuraLiveWorkload()
        val incarnation = original.sourceIncarnation
        val generation = original.generation
        original.start(OuraLiveSelection(hr = true)); original.drain()
        original.update(OuraLiveSelection(true, true)); original.drain()
        original.stop(); original.drain()
        original.start(OuraLiveSelection(motion = true)); original.drain()
        assertEquals(incarnation, original.sourceIncarnation)
        assertNotEquals(generation, original.generation)
        assertNotEquals(incarnation, OuraLiveWorkload().sourceIncarnation)
    }
}
