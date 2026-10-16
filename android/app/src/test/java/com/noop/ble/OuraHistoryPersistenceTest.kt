package com.noop.ble

import android.app.Application
import com.noop.data.StreamBatch
import com.noop.oura.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises real source ingestion and cursor persistence without opening a Bluetooth connection. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OuraHistoryPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val stored = mutableListOf<StreamBatch>()
    private val cursorsAtPersist = mutableListOf<Long>()
    private val driver = OuraDriver(ringGen = OuraRingGen.GEN3, authKey = null)
    private val source by lazy {
        OuraLiveSource(context, "oura-history-regression", OuraRingGen.GEN3, { _, _ -> }, { null },
            persist = { batch, _ ->
                cursorsAtPersist.add(OuraHistoryCursorStore.read(context, "oura-history-regression"))
                stored.add(batch)
            }).also {
            OuraLiveSource::class.java.getDeclaredField("driver").apply { isAccessible = true }.set(it, driver)
            OuraHistoryCursorStore.save(context, "oura-history-regression", 0)
        }
    }

    private fun emit(vararg events: OuraEvent) {
        OuraLiveSource::class.java.getDeclaredMethod("emit", List::class.java)
            .apply { isAccessible = true }.invoke(source, events.toList())
    }
    private fun call(name: String) {
        OuraLiveSource::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(source)
    }
    private fun commit(): Long {
        call("flush")
        OuraLiveSource::class.java.getDeclaredMethod("commitResumeCursor", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(source, false)
        return OuraHistoryCursorStore.read(context, "oura-history-regression")
    }
    private fun anchor() {
        assertTrue(driver.adoptSyncTimeAnchor(1_000_000, 1_760_000_000))
    }
    private fun motion(rt: Long = 1_000) = OuraEvent.MotionVectorEvent(
        OuraMotionEvent(rt, 0, 15, 8, 16, 24, 4, 2))

    @Test fun movementOnlyBacklogResumesAfterDeadlineInsteadOfStartingOver() {
        anchor()
        emit(motion())
        assertEquals(1_000L, commit())
        assertEquals(1, stored.sumOf { it.events.size })
    }

    @Test fun temperatureOnlyBacklogAlsoBanksItsProgress() {
        anchor()
        emit(OuraEvent.Temp(OuraTemp(2_000, 32.0)))
        assertEquals(2_000L, commit())
        assertEquals(1, stored.sumOf { it.skinTemp.size })
    }

    @Test fun parkedMovementBanksProgressOnlyAfterItGetsAnAnchor() {
        emit(motion())
        assertEquals(0L, commit())
        assertTrue(stored.isEmpty())
        anchor()
        call("drainPendingAnchorEvents")
        assertEquals(1_000L, commit())
        assertEquals(1, stored.sumOf { it.events.size })
    }

    @Test fun liveBeatCannotSkipUnfetchedHistory() {
        anchor()
        emit(motion(), OuraEvent.Ibi(OuraIBI(1_000_000, 800)))
        assertEquals(1_000L, commit())
        assertTrue(stored.any { it.rr.isNotEmpty() })
    }

    @Test fun parkedLiveBeatCannotSkipHistoryWhenAnchorArrives() {
        emit(motion(), OuraEvent.Ibi(OuraIBI(1_000_000, 800)))
        anchor()
        call("drainPendingAnchorEvents")
        assertEquals(1_000L, commit())
        assertTrue(stored.any { it.rr.isNotEmpty() })
    }

    @Test fun unanchoredTeardownNeverCreatesFalseFreshRowsAndRemainsRetryable() {
        emit(motion())
        call("drainPendingAnchorEvents")
        assertEquals(0L, commit())
        assertTrue(stored.isEmpty())
        anchor()
        call("drainPendingAnchorEvents")
        assertEquals(1_000L, commit())
        assertEquals(1, stored.sumOf { it.events.size })
    }

    @Test fun respirationOnlyBatchIsPersistedBeforeProgressIsBanked() {
        anchor()
        emit(OuraEvent.SleepPeriodInfo(OuraSleepPeriodInfo(
            3_000, 60.0, 0.0, 0.0, 0.0, 15.0, 0.0, 0, 1, 0.5)))
        assertEquals(3_000L, commit())
        assertEquals(1, stored.sumOf { it.resp.size })
    }

    @Test fun drainEndFlushesBufferedMovementBeforePublishingCursor() {
        anchor()
        emit(motion(1_000), motion(2_000), motion(3_000))
        assertTrue(stored.isEmpty())
        // Avoid replaying initial connection setup: this models an already-streaming session.
        OuraLiveSource::class.java.getDeclaredField("reachedStreaming")
            .apply { isAccessible = true }.set(source, true)
        OuraLiveSource::class.java.getDeclaredMethod("finishDrain",
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(source, false, false)
        assertEquals(3_000L, OuraHistoryCursorStore.read(context, "oura-history-regression"))
        assertEquals(3, stored.sumOf { it.events.size })
        assertEquals(listOf(0L, 0L, 0L), cursorsAtPersist)
    }
}
