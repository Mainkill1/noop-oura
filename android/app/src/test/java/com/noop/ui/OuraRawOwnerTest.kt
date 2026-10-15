package com.noop.ui

import com.noop.ble.*
import org.junit.Assert.*
import org.junit.Test

class OuraRawOwnerTest {
    @Test fun rapidHideReentryWaitsForStopAcknowledgementThenUsesNewestDesiredChannel() {
        val intents = mutableListOf<OuraRawIntent>()
        val owner = OuraRawOwner { intents.add(it) }
        fun snapshot(generation: Long, channel: String? = null) = OuraRawState("ring", 9,
            OuraRawPollSnapshot(generation = generation, channel = channel))
        owner.present(snapshot(71), "ring", true, true, "battery", 2000)
        owner.hide()
        owner.present(snapshot(71), "ring", true, true, "status:04", 500)
        owner.present(snapshot(71), "ring", true, true, "status:02", 250)
        assertEquals(2, intents.size)
        owner.present(snapshot(73), "ring", true, true, "status:02", 250)
        assertEquals(3, intents.size)
        assertEquals(73L, intents.last().generation)
        assertEquals("status:02", intents.last().channel)
        repeat(10) { owner.present(snapshot(74, "status:02"), "ring", true, true, "status:02", 250) }
        assertEquals(3, intents.size)
    }
    @Test fun authoritativeResetWhileReadinessStaysTrueReacquiresOnce() {
        val intents = mutableListOf<OuraRawIntent>()
        val owner = OuraRawOwner { intents.add(it) }
        fun present(gen: Long, channel: String? = null) = owner.present(OuraRawState("ring", 12,
            OuraRawPollSnapshot(generation = gen, channel = channel)), "ring", true, true, "event:8b", 2000)
        present(100); present(101, "event:8b")
        present(102); present(103, "event:8b"); present(103, "event:8b")
        assertEquals(listOf(100L, 102L), intents.map { it.generation })
    }
    @Test fun replacedSourceDoesNotReuseAliasAndStaleDisposalOnlyStopsItsOwnToken() {
        val intents = mutableListOf<OuraRawIntent>()
        val owner = OuraRawOwner { intents.add(it) }
        owner.present(OuraRawState("a", 1, OuraRawPollSnapshot(generation = 66)), "a", true, true, "motion", 2000)
        owner.present(OuraRawState("a", 2, OuraRawPollSnapshot(generation = 990)), "a", true, true, "battery", 250)
        assertEquals(listOf(66L, 66L, 990L), intents.map { it.generation })
        assertFalse(intents[1].foreground)
    }
}
