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

    @Test fun visibleEntryWaitsForAuthenticationThenStartsOnceWithoutSampleRetries() {
        val intents = mutableListOf<OuraLiveIntent>()
        val owner = OuraLiveOwner(intents::add)
        val snapshot = OuraLiveSessionSnapshot(sourceDeviceId = "ring", generation = 10)
        val demand = OuraLiveSelection(hr = true)
        owner.present(snapshot, "ring", true, false, demand)
        assertTrue(intents.isEmpty())
        owner.present(snapshot, "ring", true, true, demand)
        repeat(200) { owner.present(snapshot.copy(active = true, generation = 11), "ring", true, true, demand) }
        assertEquals(listOf(OuraLiveAction.Start), intents.map { it.action })
        owner.present(snapshot, "ring", true, true, OuraLiveSelection(motion = true))
        assertEquals(OuraLiveAction.Update, intents.last().action)
        owner.present(snapshot, "ring", false, true, demand)
        assertEquals(OuraLiveAction.Stop, intents.last().action)
        assertEquals(10, intents.last().generation)
    }

    @Test fun reconnectAndReentryRegainOnlyCurrentDemandAndSourceSwitchStopsAlias() {
        val intents = mutableListOf<OuraLiveIntent>()
        val owner = OuraLiveOwner(intents::add)
        val a = OuraLiveSessionSnapshot(sourceDeviceId = "a", generation = 100)
        val xyz = OuraLiveSelection(motion = true)
        owner.present(a, "a", true, true, xyz)
        owner.present(a, "a", true, false, xyz)
        owner.present(a.copy(generation = 200), "a", true, true, xyz)
        owner.present(a, "b", true, true, xyz)
        assertEquals(listOf(OuraLiveAction.Start, OuraLiveAction.Stop, OuraLiveAction.Start, OuraLiveAction.Stop), intents.map { it.action })
        owner.present(a.copy(sourceDeviceId = "b", generation = 300), "b", true, true, xyz)
        owner.present(a.copy(sourceDeviceId = "b", generation = 301), "b", true, true, OuraLiveSelection())
        assertEquals(OuraLiveAction.Stop, intents.last().action)
        owner.present(a.copy(sourceDeviceId = "b", generation = 302), "b", true, true, xyz)
        assertEquals(302, intents.last().generation)
        assertEquals(xyz, intents.last().selection)
    }

    @Test fun failedFirstSamplesAndInactiveGenerationDoNotRetryUntilNewEntry() {
        val intents = mutableListOf<OuraLiveIntent>()
        val owner = OuraLiveOwner(intents::add)
        val snapshot = OuraLiveSessionSnapshot(sourceDeviceId = "a", generation = 42)
        owner.present(snapshot, "a", true, true, OuraLiveSelection(hr = true))
        repeat(200) { owner.present(snapshot.copy(generation = 99), "a", true, true, OuraLiveSelection(hr = true)) }
        assertEquals(1, intents.size)
        owner.hide()
        owner.present(snapshot.copy(generation = 99), "a", true, true, OuraLiveSelection(hr = true))
        assertEquals(2, intents.size)
        assertEquals(99L, intents.last().generation)
    }

    @Test fun rapidReentryWaitsForPriorOffThenStartsOnceAndIgnoresStaleSource() {
        val intents = mutableListOf<OuraLiveIntent>()
        val owner = OuraLiveOwner(intents::add)
        val snapshot = OuraLiveSessionSnapshot(sourceDeviceId = "a", generation = 10)
        val hr = OuraLiveSelection(hr = true)
        owner.present(snapshot, "a", true, true, hr)
        owner.present(snapshot.copy(active = true, generation = 11), "a", true, true, hr)
        owner.hide()
        owner.present(snapshot.copy(active = true, generation = 11), "a", true, true, hr)
        assertEquals(2, intents.size)
        owner.present(snapshot.copy(generation = 12), "a", true, true, hr)
        assertEquals(OuraLiveIntent(OuraLiveAction.Start, "a", 12, hr), intents.last())
        owner.present(snapshot, "b", true, true, hr)
        owner.present(snapshot.copy(sourceDeviceId = "b", generation = 20), "b", true, true, hr)
        assertEquals(OuraLiveIntent(OuraLiveAction.Start, "b", 20, hr), intents.last())
    }

    @Test fun rapidPendingStartReentryWaitsForStopGenerationAcknowledgement() {
        val intents = mutableListOf<OuraLiveIntent>()
        val owner = OuraLiveOwner(intents::add)
        val pending = OuraLiveSessionSnapshot(sourceDeviceId = "a", generation = 100)
        val hr = OuraLiveSelection(hr = true)
        owner.present(pending, "a", true, true, hr)
        owner.hide()
        owner.present(pending, "a", true, true, hr)
        assertEquals(listOf(OuraLiveAction.Start, OuraLiveAction.Stop), intents.map { it.action })
        owner.present(pending.copy(generation = 102), "a", true, true, hr)
        assertEquals(OuraLiveIntent(OuraLiveAction.Start, "a", 102, hr), intents.last())
        repeat(100) { owner.present(pending.copy(generation = 102), "a", true, true, hr) }
        assertEquals(3, intents.size)
    }

    @Test fun sameIdSourceReplacementRegainsDemandOnceWhileReadyNeverDrops() {
        val intents = mutableListOf<OuraLiveIntent>()
        val owner = OuraLiveOwner(intents::add)
        val hr = OuraLiveSelection(hr = true)
        val first = OuraLiveSessionSnapshot(sourceDeviceId = "a", sourceIncarnation = 41, generation = 100)
        owner.present(first, "a", true, true, hr)
        owner.present(first.copy(active = true, generation = 101), "a", true, true, hr)
        val replacement = first.copy(sourceIncarnation = 42, generation = 200)
        owner.present(replacement, "a", true, true, hr)
        assertEquals(listOf(100L, 200L), intents.filter { it.action == OuraLiveAction.Start }.map { it.generation })
        repeat(200) { index ->
            owner.present(replacement.copy(generation = 201L + index,
                hr = OuraLiveStream(phase = OuraLiveStreamPhase.UNAVAILABLE)), "a", true, true, hr)
        }
        assertEquals(2, intents.count { it.action == OuraLiveAction.Start })
        owner.present(replacement.copy(sourceIncarnation = 43, generation = 500), "a", true, true, hr)
        assertEquals(listOf(100L, 200L, 500L), intents.filter { it.action == OuraLiveAction.Start }.map { it.generation })
    }

    @Test fun replacementAfterScreenObservationStartsOnceWithoutAnInterveningNotReadyFrame() {
        val intents = mutableListOf<OuraLiveIntent>()
        val owner = OuraLiveOwner(intents::add)
        val hr = OuraLiveSelection(hr = true)
        val original = OuraLiveSessionSnapshot(sourceDeviceId = "a", sourceIncarnation = 11, generation = 10)
        owner.present(original, "a", true, true, hr)
        owner.observe(original.copy(active = true, generation = 12))
        val replacement = original.copy(sourceIncarnation = 12, generation = 20)
        owner.observe(replacement)
        owner.present(replacement, "a", true, true, hr)
        repeat(200) { owner.present(replacement.copy(generation = 21), "a", true, true, hr) }
        assertEquals(listOf(10L, 20L), intents.filter { it.action == OuraLiveAction.Start }.map { it.generation })
    }
}
