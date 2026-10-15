package com.noop.data

import android.app.Application
import androidx.room.Room
import com.noop.oura.OuraLiveMotionSummary
import com.noop.oura.OuraRawXYZ
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OuraLiveMotionPersistenceTest {
    @Test fun concurrentPartialSessionsMergeAtomicallyWithoutTouchingOtherSourceOrRingMotion() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WhoopDatabase::class.java).build()
        try {
            val repo = WhoopRepository(db)
            repo.upsertDevice("oura-a"); repo.upsertDevice("oura-b")
            fun batch(ms: Long, x: Int): StreamBatch {
                val summary = OuraLiveMotionSummary.sample(ms, OuraRawXYZ(x, -x, 3))
                return StreamBatch(events = listOf(EventEntry(summary.ts, OuraLiveMotionSummary.EVENT_KIND, summary.payloadJSON())))
            }
            repo.insert(StreamBatch(events = listOf(EventEntry(1000, OuraStreamMapping.EVENT_MOTION, "{\"motion_seconds\":3}"))), "oura-a")
            coroutineScope {
                (0 until 20).map { index -> async(Dispatchers.Default) { repo.insert(batch(1000000L + index, index), "oura-a") } }.awaitAll()
            }
            repo.insert(batch(1000001, 99), "oura-b")
            val data = OuraDashboardData.load(repo, OuraDashboardSource("oura-a", "A"), 999, 1002, 1)
            val motion = data.liveMotionSeconds.single()
            assertEquals(20L, motion.count)
            assertEquals(9.5, motion.mean("x"), 0.0)
            assertEquals(0L, motion.min("x")); assertEquals(19L, motion.max("x"))
            assertEquals(1000000L, motion.firstReceiptEpochMs); assertEquals(1000019L, motion.lastReceiptEpochMs)
            assertEquals(3.0, data.motionWindows.single().seconds, 0.0)
            assertEquals(1L, OuraDashboardData.load(repo, OuraDashboardSource("oura-b", "B"), 999, 1002, 1).liveMotionSeconds.single().count)
            assertTrue(data.signals.containsKey("live_motion_x_max"))
            assertTrue(data.signals.containsKey("movement"))
        } finally { db.close() }
    }
}
