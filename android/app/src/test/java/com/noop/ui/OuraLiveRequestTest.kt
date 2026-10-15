package com.noop.ui

import com.noop.ble.*
import org.junit.Assert.*
import org.junit.Test

class OuraLiveRequestTest {
    @Test fun axesAreOneSubscriptionAndSleepRequestsNothing() {
        assertEquals(OuraLiveSelection(motion = true), ouraLiveSelection(OuraCategory.Movement, emptySet()))
        assertEquals(OuraLiveSelection(hr = true, motion = true), ouraLiveSelection(OuraCategory.Compare, setOf("hr", "x", "z")))
        assertEquals(OuraLiveSelection(), ouraLiveSelection(OuraCategory.Sleep, setOf("hr")))
    }

    @Test fun immediateLeaveCancelsPendingStartWithUnmodifiedOpaqueAlias() {
        val intents = mutableListOf<OuraLiveIntent>()
        val owner = OuraLiveOwner(intents::add)
        val snapshot = OuraLiveSessionSnapshot(sourceDeviceId = "ring", generation = 987654321L)
        owner.start(snapshot, OuraLiveSelection(hr = true))
        owner.start(snapshot, OuraLiveSelection(hr = true))
        owner.stop()
        owner.stop()
        assertEquals(listOf(
            OuraLiveIntent(OuraLiveAction.Start, "ring", 987654321L, OuraLiveSelection(hr = true)),
            OuraLiveIntent(OuraLiveAction.Stop, "ring", 987654321L)), intents)
    }

    @Test fun selectionChangesNeverStartAndDisposedOwnerCannotStopNewOwner() {
        val intents = mutableListOf<OuraLiveIntent>()
        val old = OuraLiveOwner(intents::add)
        val fresh = OuraLiveOwner(intents::add)
        val snapshot = OuraLiveSessionSnapshot(sourceDeviceId = "ring", generation = 14)
        old.update(OuraLiveSelection(motion = true))
        assertTrue(intents.isEmpty())
        old.start(snapshot, OuraLiveSelection(hr = true))
        old.stop()
        fresh.start(snapshot.copy(generation = 6789), OuraLiveSelection(motion = true))
        old.stop()
        assertEquals(3, intents.size)
        assertEquals(6789L, intents.last().generation)
    }

    @Test fun automaticStopCannotRestartThroughCategoryChange() {
        val intents = mutableListOf<OuraLiveIntent>()
        val owner = OuraLiveOwner(intents::add)
        val initial = OuraLiveSessionSnapshot(sourceDeviceId = "ring", generation = 10000)
        owner.start(initial, OuraLiveSelection(motion = true))
        owner.observe(initial.copy(active = true, generation = 55555))
        owner.observe(initial.copy(generation = 88888))
        owner.update(OuraLiveSelection(hr = true))
        assertEquals(1, intents.size)
        owner.start(initial.copy(generation = 88888), OuraLiveSelection(hr = true))
        assertEquals(88888L, intents.last().generation)
    }
}
