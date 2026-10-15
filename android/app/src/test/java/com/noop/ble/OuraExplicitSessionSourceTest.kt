package com.noop.ble

import android.app.Application
import android.os.Looper
import com.noop.oura.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OuraExplicitSessionSourceTest {
    private val live = mutableListOf<Pair<Int,List<Int>>>()
    private val source = OuraLiveSource(RuntimeEnvironment.getApplication(), "oura-session-test", OuraRingGen.GEN3,
        { hr, rr -> live.add(hr to rr) }, { null })
    private val driver = OuraDriver(OuraRingGen.GEN3, null).also {
        it.liveHRWanted = false
        it.nextStep(OuraTransition.AuthCompleted(OuraAuthStatus.SUCCESS))
    }
    private fun set(name: String, value: Any) = OuraLiveSource::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(source, value)
    private fun ready() { set("driver", driver); set("reachedStreaming", true) }
    private fun notification(vararg bytes: Int) = OuraLiveSource::class.java.getDeclaredMethod("handleNotification", ByteArray::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(source, bytes.map(Int::toByte).toByteArray(), source.liveSession.value.generation, android.os.SystemClock.elapsedRealtime())

    @Test fun actualNotificationsReachMemoryOnlyDuringRequestedSession() {
        ready()
        notification(0x33,8,50,7,1,0,2,0,3,0)
        assertTrue(source.liveSession.value.motion.samples.isEmpty())
        source.startLiveSession(OuraLiveSelection(true, true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        notification(0x33,8,50,7,1,0,2,0,3,0)
        assertEquals(1, source.liveSession.value.motion.samples.size)
        assertEquals(OuraLiveStreamPhase.WAITING, source.liveSession.value.hr.phase)
        notification(0x2f,15,0x28,2,0,2,0,0,1,4,0,0,0,0,0,0,127)
        assertEquals(listOf(59), source.liveSession.value.hr.samples.map { it.bpm })
        source.stopLiveSession()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        notification(0x33,8,50,8,4,0,5,0,6,0)
        assertFalse(source.liveSession.value.active)
        assertEquals(1, source.liveSession.value.motion.samples.size)
        assertEquals(OuraLiveStreamPhase.IDLE, source.liveSession.value.motion.phase)
    }
    @Test fun historicalIbiCannotReachTheLiveSinkOrSession() {
        ready()
        source.startLiveSession(OuraLiveSelection(hr = true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        OuraLiveSource::class.java.getDeclaredMethod("emit", List::class.java).apply { isAccessible = true }
            .invoke(source, listOf(OuraEvent.Ibi(OuraIBI(1000,800))))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertTrue(live.isEmpty())
        assertTrue(source.liveSession.value.hr.samples.isEmpty())
        source.stopLiveSession()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
    }
    @Test fun motionDeadlineStopsSessionAndSelectionUpdatesCannotExtendIt() {
        ready()
        source.startLiveSession(OuraLiveSelection(motion = true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertTrue(source.liveSession.value.active)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        source.updateLiveSession(OuraLiveSelection(true, true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertFalse(source.liveSession.value.active)
    }
    @Test fun startsRequireAuthenticationAndRestartDoesNotRestoreOldDemand() {
        source.startLiveSession(OuraLiveSelection(true,true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertFalse(source.liveSession.value.active)
        ready()
        source.startLiveSession(OuraLiveSelection(true,true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertTrue(source.liveSession.value.active)
        source.stop()
        assertFalse(source.liveSession.value.active)
    }
    @Test fun historyYieldsOnlyAfterTheCurrentPageAndFlushesBeforeResume() {
        ready()
        driver.adoptSyncTimeAnchor(1_000_000, 1_760_000_000)
        OuraLiveSource::class.java.getDeclaredMethod("fetchHistoryIfIdle").apply { isAccessible = true }.invoke(source)
        assertEquals(OuraHistorySyncPhase.SYNCING, source.historySyncPhase.value)
        source.startLiveSession(OuraLiveSelection(motion = true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertEquals(OuraHistorySyncPhase.SYNCING, source.historySyncPhase.value)
        notification(0x11,8,1,0,10,0,0,0,0,0)
        notification(0x47,8,0xe8,3,0,0,0,1,2,3)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1499))
        assertEquals(OuraHistorySyncPhase.SYNCING, source.historySyncPhase.value)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(OuraHistorySyncPhase.PAUSED_FOR_LIVE, source.historySyncPhase.value)
        assertEquals(1000L, OuraHistoryCursorStore.read(RuntimeEnvironment.getApplication(), "oura-session-test"))
        source.stopLiveSession()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertEquals(OuraHistorySyncPhase.SYNCING, source.historySyncPhase.value)
    }

    @Test fun rapidUiIntentsCannotResetSamplesOrRenewOriginalDeadline() {
        ready()
        source.startLiveSession(OuraLiveSelection(true,true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        notification(0x33,8,50,7,1,0,2,0,3,0)
        val deadline = source.liveSession.value.motionDeadlineElapsedMs
        val started = source.liveSession.value.startedAtElapsedMs
        repeat(200) {
            source.startLiveSession(OuraLiveSelection(hr = true))
            source.updateLiveSession(OuraLiveSelection(motion = true))
            source.updateLiveSession(OuraLiveSelection(true,true))
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertEquals(deadline, source.liveSession.value.motionDeadlineElapsedMs)
        assertEquals(started, source.liveSession.value.startedAtElapsedMs)
        assertEquals(1, source.liveSession.value.motion.samples.size)
        source.stop()
    }
    @Test fun activityStopEndsSessionAndUpdateCannotRestoreIt() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        ready()
        source.startLiveSession(OuraLiveSelection(true,true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertTrue(source.liveSession.value.active)
        activity.pause().stop()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        source.updateLiveSession(OuraLiveSelection(true,true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertFalse(source.liveSession.value.active)
        activity.destroy()
    }

    @Test fun cachedOwnerCanCancelPendingStartButCannotTouchLaterSession() {
        ready()
        val initial = source.liveSession.value.generation
        source.startLiveSession(OuraLiveSelection(true,true), "oura-session-test", initial)
        source.stopLiveSession("oura-session-test", initial)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertFalse(source.liveSession.value.active)
        val stopped = source.liveSession.value.generation
        source.startLiveSession(OuraLiveSelection(hr = true), "oura-session-test", stopped)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertTrue(source.liveSession.value.active)
        source.stopLiveSession("oura-session-test", initial)
        source.updateLiveSession(OuraLiveSelection(motion = true), "oura-other-ring", source.liveSession.value.generation)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertTrue(source.liveSession.value.active)
        assertEquals(OuraLiveSelection(hr = true), source.liveSession.value.selection)
        source.stop()
    }
    @Test fun silentChannelTimesOutWithoutStoppingTheOtherStreamOrRetrying() {
        ready()
        source.startLiveSession(OuraLiveSelection(true,true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        notification(0x33,8,50,7,1,0,2,0,3,0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertTrue(source.liveSession.value.active)
        assertEquals(OuraLiveStreamPhase.UNAVAILABLE, source.liveSession.value.hr.phase)
        assertEquals(1, source.liveSession.value.motion.samples.size)
        notification(0x2f,15,0x28,2,0,2,0,0,1,4,0,0,0,0,0,0,127)
        assertTrue(source.liveSession.value.hr.samples.isEmpty())
        source.stop()
    }
    @Test fun truncatedNotificationCannotSmuggleLiveMotionPrefix() {
        ready()
        source.startLiveSession(OuraLiveSelection(motion = true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        notification(0x33,8,50,7,1,0,2,0,3,0,0x33)
        assertTrue(source.liveSession.value.motion.samples.isEmpty())
        source.stop()
    }

}
