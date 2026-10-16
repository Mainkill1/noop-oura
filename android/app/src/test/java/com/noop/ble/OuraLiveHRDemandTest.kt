package com.noop.ble

import org.junit.Assert.*
import org.junit.Test
import com.noop.oura.OuraCommands
import com.noop.oura.OuraDriverPhase

class OuraLiveHRDemandTest {
    @Test fun liveExitRestoresAutomaticMeasurementBeforeUnsubscribing() {
        val demand = OuraLiveHRDemand()
        demand.authenticated()
        demand.update(true, true)
        val exit = demand.update(false, true)
        assertEquals(2, exit.size)
        assertArrayEquals(intArrayOf(0x2f, 3, 0x22, 2, 1), exit[0].bytes)
        assertArrayEquals(intArrayOf(0x2f, 3, 0x26, 2, 0), exit[1].bytes)
    }
    @Test fun leavingHeartRateViewUnsubscribesOnceWithoutDisablingBackgroundMeasurement() {
        val demand = OuraLiveHRDemand()
        demand.authenticated()
        assertEquals(listOf("dhr_automatic", "dhr_unsubscribe"), demand.update(false, true).map { it.label })
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
        assertEquals(listOf("dhr_automatic", "dhr_unsubscribe"), demand.update(false, true).map { it.label })
    }

    @Test fun repeatedHistoryUpdatesNeverRepeatTheInitialStop() {
        val demand = OuraLiveHRDemand()
        demand.authenticated()
        demand.update(false, true)
        assertTrue(demand.update(false, true).isEmpty())
    }

    @Test fun newConnectionClearsSubscriptionLeftByPriorProcess() {
        val demand = OuraLiveHRDemand()
        demand.authenticated()
        assertEquals(listOf("dhr_automatic", "dhr_unsubscribe"),
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
            queue.finishWith(listOf(OuraCommands.liveHRRestoreAutomatic(), OuraCommands.liveHRUnsubscribe())) { drained = true }
        } else queue.close()
        repeat(3) { queue.complete(true) }
        assertEquals(listOf("get_events", "dhr_automatic", "dhr_unsubscribe"), sent)
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
