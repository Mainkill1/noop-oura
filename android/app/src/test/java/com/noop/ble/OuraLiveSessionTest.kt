package com.noop.ble

import com.noop.oura.*
import org.junit.Assert.*
import org.junit.Test

class OuraLiveSessionTest {
    @Test fun requestDoesNotClaimReceivingUntilAnActualSelectedSampleArrives() {
        val session = OuraLiveSession()
        session.start(OuraLiveSelection(hr = true), 100)
        assertEquals(OuraLiveStreamPhase.WAITING, session.snapshot.hr.phase)
        assertFalse(session.noteMotion(OuraLiveMotionPacket(0x31, 3, listOf(OuraRawXYZ(1,2,3))), 200))
        assertTrue(session.noteHr(75, 800, 300))
        assertEquals(OuraLiveStreamPhase.RECEIVING, session.snapshot.hr.phase)
        assertEquals(300L, session.snapshot.hr.lastReceiptElapsedMs)
        session.tick(5_301)
        assertEquals(OuraLiveStreamPhase.STALE, session.snapshot.hr.phase)
    }
    @Test fun streamsHaveIndependentFreshnessAndNeverFillGaps() {
        val session = OuraLiveSession()
        session.start(OuraLiveSelection(true, true), 0)
        session.noteHr(60, 1000, 1_000)
        session.noteMotion(OuraLiveMotionPacket(0x31, 1, listOf(OuraRawXYZ(-1, 0, 1))), 2_000)
        session.tick(3_501)
        assertEquals(OuraLiveStreamPhase.RECEIVING, session.snapshot.hr.phase)
        assertEquals(OuraLiveStreamPhase.STALE, session.snapshot.motion.phase)
        assertEquals(1, session.snapshot.hr.samples.size)
        assertEquals(1, session.snapshot.motion.samples.size)
    }
    @Test fun restartAndStopCannotAcceptOldOrUnselectedStreams() {
        val session = OuraLiveSession()
        session.start(OuraLiveSelection(hr = true), 10)
        session.noteHr(60, 1000, 20)
        session.stop()
        assertFalse(session.noteHr(70, 857, 30))
        session.start(OuraLiveSelection(motion = true), 40)
        assertTrue(session.snapshot.hr.samples.isEmpty())
        assertFalse(session.noteHr(70, 857, 50))
        assertTrue(session.snapshot.motion.samples.isEmpty())
    }
    @Test fun sixtySecondsAndCountBoundMemoryWhileKeepingActualValues() {
        val session = OuraLiveSession()
        session.start(OuraLiveSelection(hr = true), 0)
        repeat(7000) { session.noteHr(50 + it % 10, null, it.toLong()) }
        assertTrue(session.snapshot.hr.samples.size <= 6_000)
        session.noteHr(219, null, 70_000)
        assertEquals(listOf(219), session.snapshot.hr.samples.map { it.bpm })
    }
    @Test fun freshnessRetainsLastActualReceiptAfterPlotWindowExpires() {
        val session = OuraLiveSession()
        session.start(OuraLiveSelection(hr = true), 0)
        session.noteHr(75, 800, 1_000)
        session.tick(70_000)
        assertTrue(session.snapshot.hr.samples.isEmpty())
        assertEquals(1_000L, session.snapshot.hr.lastReceiptElapsedMs)
        assertEquals(OuraLiveStreamPhase.STALE, session.snapshot.hr.phase)
    }

}
