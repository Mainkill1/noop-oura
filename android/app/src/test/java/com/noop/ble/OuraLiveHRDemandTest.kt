package com.noop.ble

import org.junit.Assert.*
import org.junit.Test
import com.noop.oura.OuraCommands
import com.noop.oura.OuraDriverPhase

class OuraLiveHRDemandTest {
    @Test fun leavingHeartRateViewDisablesAndUnsubscribesExactlyOnce() {
        val demand = OuraLiveHRDemand()
        demand.authenticated()
        assertEquals(listOf("dhr_disable", "dhr_unsubscribe"), demand.update(false, true).map { it.label })
        assertTrue(demand.update(false, true).isEmpty())
    }

    @Test fun returningToHeartRateViewEnablesAndSubscribesExactlyOnce() {
        val demand = OuraLiveHRDemand()
        demand.authenticated()
        demand.update(false, true)
        assertEquals(listOf("dhr_enable", "dhr_subscribe"), demand.update(true, true).map { it.label })
        assertTrue(demand.update(true, true).isEmpty())
    }

    @Test fun viewChangeDuringHandshakeWaitsForItsCompletion() {
        val demand = OuraLiveHRDemand()
        demand.authenticated()
        assertTrue(demand.update(false, false).isEmpty())
        assertEquals(listOf("dhr_disable", "dhr_unsubscribe"), demand.update(false, true).map { it.label })
    }

    @Test fun repeatedHistoryUpdatesNeverRepeatTheInitialStop() {
        val demand = OuraLiveHRDemand()
        demand.authenticated()
        demand.update(false, true)
        assertTrue(demand.update(false, true).isEmpty())
    }

    @Test fun newConnectionClearsLiveModeLeftByPriorProcess() {
        val demand = OuraLiveHRDemand()
        demand.authenticated()
        assertEquals(listOf("dhr_disable", "dhr_unsubscribe"),
            demand.update(false, true).map { it.label })
    }

    @Test fun freshConnectionDoesNotInheritThePreviousEnable() {
        val demand = OuraLiveHRDemand()
        demand.authenticated()
        demand.reset()
        assertFalse(demand.needsShutdown)
        assertTrue(demand.update(false, false).isEmpty())
        demand.authenticated()
        demand.update(false, true)
        assertEquals(2, demand.update(true, true).size)
    }

    @Test fun releaseThenStopDrainsShutdownEvenBeforeTheDisableWriteStarts() {
        val demand = OuraLiveHRDemand()
        val sent = mutableListOf<String>()
        val queue = OuraWriteQueue(submit = { sent.add(it.label); true })
        demand.authenticated()
        queue.enqueue(OuraCommands.getEvents(0, 255))
        demand.update(false, true).forEach(queue::enqueue)
        var drained = false
        if (demand.needsShutdown) {
            queue.finishWith(listOf(OuraCommands.liveHRDisable(), OuraCommands.liveHRUnsubscribe())) { drained = true }
        } else queue.close()
        repeat(3) { queue.complete(true) }
        assertEquals(listOf("get_events", "dhr_disable", "dhr_unsubscribe"), sent)
        assertTrue(drained)
    }

    @Test fun explicitLiveKeepsStreamingWhileHistoryIsFetching() {
        val demand = OuraLiveHRDemand()
        assertEquals(listOf("dhr_enable", "dhr_subscribe"),
            demand.keepAlive(true, OuraDriverPhase.FetchingHistory).map { it.label })
        assertTrue(demand.keepAlive(false, OuraDriverPhase.FetchingHistory).isEmpty())
        assertTrue(demand.keepAlive(true, OuraDriverPhase.Authenticating).isEmpty())
    }
}
