package com.noop.ble

import org.junit.Assert.*
import org.junit.Test

class OuraHistoryTransportTest {
    private class Link : OuraHistoryTransport.Link {
        val requests = mutableListOf<String>()
        var accepted = true
        override fun priority(high: Boolean): Boolean { requests += if (high) "high" else "balanced"; return accepted }
        override fun readPhy(): Boolean { requests += "read"; return accepted }
        override fun preferPhy(tx: Int, rx: Int): Boolean { requests += "$tx/$rx"; return accepted }
    }
    private fun transport(link: Link, twoM: Boolean = true) = OuraHistoryTransport("synthetic", twoM, link)

    @Test fun historyOwnsOneBoostAndRestoresBaselineEvenBeforeUpdateCallback() {
        val link = Link(); val transfer = transport(link)
        transfer.begin(100, 1000); transfer.begin(101, 1001)
        assertEquals(listOf("high", "read"), link.requests)
        transfer.phyRead(1, 1, success = true)
        assertEquals("2/2", link.requests.last())
        transfer.finish(OuraHistoryTransferOutcome.YIELDED, 300, 1200)
        assertEquals(listOf("high", "read", "2/2", "balanced", "1/1"), link.requests)
        transfer.phyUpdated(2, 2, success = true)
        transfer.finish(OuraHistoryTransferOutcome.COMPLETE, 500, 1400)
        assertEquals(5, link.requests.size)
        assertFalse(transfer.snapshot(500).boostRequested)
        assertEquals(OuraHistoryTransferOutcome.YIELDED, transfer.snapshot(500).outcome)
        assertEquals(2, transfer.snapshot(500).txPhy)
    }

    @Test fun lateBaselineCannotStartTuningAfterExitAndNextPassUsesSameConnectionBaseline() {
        val link = Link(); val transfer = transport(link)
        transfer.begin(0, 1000)
        transfer.finish(OuraHistoryTransferOutcome.COMPLETE, 50, 1050)
        transfer.phyRead(1, 2, success = true)
        assertEquals(listOf("high", "read", "balanced"), link.requests)
        transfer.begin(100, 1100)
        assertEquals(listOf("high", "read", "balanced", "high", "2/2"), link.requests)
        transfer.finish(OuraHistoryTransferOutcome.STOPPED, 200, 1200)
        assertEquals("1/2", link.requests.last())
    }

    @Test fun unknownFailedCodedAndUnsupportedPhyLeaveOrdinaryHistoryAvailable() {
        listOf(Triple(false, 1, true), Triple(true, 3, true), Triple(true, 1, false)).forEach { (supported, phy, success) ->
            val link = Link(); val transfer = transport(link, supported)
            transfer.begin(0, 1000)
            transfer.phyRead(phy, phy, success)
            transfer.notification(20, 1)
            transfer.finish(OuraHistoryTransferOutcome.COMPLETE, 100, 1100)
            assertTrue(link.requests.none { '/' in it })
            assertEquals(20L, transfer.snapshot(100).wireBytes)
        }
        val link = Link().apply { accepted = false }; val transfer = transport(link)
        transfer.begin(0, 1000); transfer.phyRead(1, 1, true)
        transfer.finish(OuraHistoryTransferOutcome.COMPLETE, 100, 1100)
        assertEquals(listOf("high", "read", "balanced"), link.requests)
        assertEquals(OuraHistoryTransferOutcome.COMPLETE, transfer.snapshot(100).outcome)
    }

    @Test fun leaseExpiryRestoresPreferencesWithoutCompletingOrRestartingHistory() {
        val link = Link(); val transfer = transport(link)
        transfer.begin(100, 1000); transfer.phyRead(1, 1, true)
        assertEquals(false, transfer.expireBoost(300099))
        assertTrue(transfer.snapshot(300099).boostRequested)
        assertEquals(true, transfer.expireBoost(300100))
        assertEquals(false, transfer.expireBoost(300101))
        assertEquals(listOf("balanced", "1/1"), link.requests.takeLast(2))
        transfer.phyUpdated(2, 2, true); transfer.phyRead(1, 1, true)
        transfer.begin(300200, 301100)
        assertEquals(5, link.requests.size)
        assertEquals(OuraHistoryTransferOutcome.ACTIVE, transfer.snapshot(300200).outcome)
        assertFalse(transfer.snapshot(300200).boostRequested)
    }

    @Test fun rejectedRestorationIsReportedWithoutPretendingTheLinkChangedOrRetrying() {
        val link = Link(); val transfer = transport(link)
        transfer.begin(0, 1000); transfer.phyRead(1, 1, true)
        transfer.phyUpdated(2, 2, true)
        link.accepted = false
        transfer.finish(OuraHistoryTransferOutcome.STOPPED, 100, 1100)
        val recap = transfer.snapshot(100)
        assertEquals(false, recap.balancedRequestAccepted)
        assertEquals(false, recap.phyRestoreSubmitted)
        assertEquals(2, recap.txPhy)
        assertFalse(recap.boostRequested)
        transfer.expireBoost(400000)
        assertEquals(5, link.requests.size)
    }

    @Test fun disconnectedLinkDoesNotReceiveRestorationRequests() {
        val link = Link(); val transfer = transport(link)
        transfer.begin(0, 1000); transfer.phyRead(1, 1, true)
        transfer.finish(OuraHistoryTransferOutcome.DISCONNECTED, 100, 1100, connected = false)
        assertEquals(listOf("high", "read", "2/2"), link.requests)
        transfer.phyUpdated(2, 2, true)
        assertEquals(3, link.requests.size)
        assertFalse(transfer.snapshot(100).boostRequested)
    }

    @Test fun recapCountsTrafficOnlyInsideDrainAndNeverCallsItPersistedData() {
        val transfer = transport(Link())
        transfer.notification(100, 2)
        transfer.begin(100, 1000)
        transfer.mtu(247, true); transfer.mtu(517, false)
        transfer.notification(20, 1); transfer.notification(200, 10); transfer.notification(40, 0)
        transfer.records(5, 3)
        transfer.finish(OuraHistoryTransferOutcome.COMPLETE, 1100, 2000)
        transfer.notification(200, 5)
        val recap = transfer.snapshot(2000)
        assertEquals(3L, recap.notifications); assertEquals(260L, recap.wireBytes)
        assertEquals(11L, recap.outerFrames); assertEquals(1L, recap.packedNotifications)
        assertEquals(200, recap.maxNotificationBytes); assertEquals(listOf(1L, 1L, 1L), recap.notificationSizes)
        assertEquals(5L, recap.records); assertEquals(3L, recap.decodedEvents)
        assertEquals(1000L, recap.elapsedMs); assertEquals(2000L, recap.endedAtMs)
        assertEquals(247, recap.mtu)
        transfer.begin(2000, 3000)
        assertEquals(0L, transfer.snapshot(2000).wireBytes)
        assertEquals(247, transfer.snapshot(2000).mtu)
    }
}
