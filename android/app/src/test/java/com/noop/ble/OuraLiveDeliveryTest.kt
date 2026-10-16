package com.noop.ble

import org.junit.Assert.*
import org.junit.Test

class OuraLiveDeliveryTest {
    @Test fun oneNotificationWithFourReadingsIsDistinctFromFourCallbacks() {
        val packed = listOf(60, 70, 80, 90).map { OuraLiveHrSample(1000, it, null, 1003, 9) }
        val result = ouraLiveDelivery(packed)
        assertEquals(4, result.readings)
        assertEquals(1, result.notifications)
        assertEquals(4, result.maxReadingsPerNotification)
        assertEquals(0L, result.minReceiveGapMs)
        assertEquals(0L, result.maxReceiveGapMs)
        assertEquals(3L, result.maxQueueWaitMs)
        val callbacks = listOf(1000L, 1010, 1020, 1030).mapIndexed { i, at -> OuraLiveHrSample(at, 60+i, null, at+2, i.toLong()) }
        val separate = ouraLiveDelivery(callbacks)
        assertEquals(4, separate.notifications)
        assertEquals(1, separate.maxReadingsPerNotification)
        assertEquals(10L, separate.lastReceiveGapMs)
    }

    @Test fun delayedOwnerDoesNotMakeSpacedCallbacksIntoSimultaneousMeasurements() {
        val samples = listOf(1000L, 2000, 3000, 4000).mapIndexed { i, at -> OuraLiveHrSample(at, 60+i, null, 4000, i.toLong()) }
        val result = ouraLiveDelivery(samples)
        assertEquals(1000L, result.minReceiveGapMs)
        assertEquals(1000L, result.maxReceiveGapMs)
        assertEquals(3000L, result.maxQueueWaitMs)
        assertEquals(0L, result.lastQueueWaitMs)
        assertEquals(listOf(1000L, 2000, 3000, 4000), samples.map { it.receivedAtElapsedMs })
        assertEquals(listOf(60,61,62,63), samples.map { it.bpm })
    }

    @Test fun incompleteOrInvalidMetadataStaysUnknownAndReorderingIsVisible() {
        val result = ouraLiveDelivery(listOf(OuraLiveHrSample(100,60,null,90,1), OuraLiveHrSample(90,70,null,100)))
        assertNull(result.notifications)
        assertNull(result.maxReadingsPerNotification)
        assertEquals(1, result.outOfOrderReceipts)
        assertNull(result.minReceiveGapMs)
        assertEquals(10L, result.lastQueueWaitMs)
        assertEquals(10L, result.maxQueueWaitMs)
        assertNull(ouraLiveDelivery(listOf(OuraLiveHrSample(100,60,null,90))).lastQueueWaitMs)
    }

    @Test fun emptyAndSingleReadingNeverInventACadence() {
        val empty = ouraLiveDelivery(emptyList())
        assertEquals(0, empty.readings)
        assertNull(empty.lastReceiveGapMs)
        assertNull(empty.lastQueueWaitMs)
        val single = ouraLiveDelivery(listOf(OuraLiveHrSample(10,60,null,12,4)))
        assertEquals(1, single.notifications)
        assertNull(single.minReceiveGapMs)
        assertNull(single.maxReceiveGapMs)
        assertEquals(2L, single.lastQueueWaitMs)
    }
}
