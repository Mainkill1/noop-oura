package com.noop.ble

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import com.noop.oura.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OuraRawSourceTest {
    private val source = OuraLiveSource(RuntimeEnvironment.getApplication(), "raw-test", OuraRingGen.GEN3,
        { _, _ -> }, { null }, persist = { _, _ -> })
    private val driver = OuraDriver(OuraRingGen.GEN3, null).also {
        it.liveHRWanted = false
        it.nextStep(OuraTransition.AuthCompleted(OuraAuthStatus.SUCCESS))
    }
    private fun ready() {
        OuraLiveSource::class.java.getDeclaredField("driver").apply { isAccessible = true }.set(source, driver)
        OuraLiveSource::class.java.getDeclaredField("reachedStreaming").apply { isAccessible = true }.set(source, true)
    }
    private fun notify(vararg bytes: Int) {
        OuraLiveSource::class.java.getDeclaredMethod("handleNotification", ByteArray::class.java,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(source, bytes.map(Int::toByte).toByteArray(), source.liveSession.value.generation, SystemClock.elapsedRealtime())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(201))
    }
    @Test fun validatedReceiveTapRetainsUnknownAndLowQualityBeforeTypedIngestionAndScopesSession() {
        ready()
        notify(0x90, 6, 1, 0, 0, 0, 255, 0)
        notify(0x80, 8, 2, 0, 0, 0, 0, 0, 255, 31)
        assertEquals(listOf("event:90", "event:80"), source.rawInstruments.value.capture.records.map { it.observation.channel })
        assertEquals(listOf(0.0, 2047.0), source.rawInstruments.value.capture.records.last().observation.fields.first { it.key == "ibi" }.values)
        source.stop()
        assertTrue(source.rawInstruments.value.capture.records.isEmpty())
    }
    @Test fun malformedOuterPrefixAndPrivateOrUnknownSecureBodiesNeverEnterState() {
        ready()
        notify(0x80, 6, 1, 0, 0, 0, 100, 8, 0x33, 8, 0)
        notify(0x43, 7, 1, 0, 0, 0, 65, 66, 67)
        notify(0x2f, 3, 0x28, 99, 12)
        notify(0x2f, 4, 0x04, 0xaa, 0xbb, 0xcc)
        assertTrue(source.rawInstruments.value.capture.records.isEmpty())
        source.stop()
    }
    @Test fun backgroundStopAndReplacementIncarnationRejectOldOwnerIntent() {
        ready()
        val initial = source.rawInstruments.value
        source.rawInstrumentsIntent("raw-test", initial.sourceIncarnation, initial.poll.generation, "event:8b", 250, true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(151))
        assertEquals("event:8b", source.rawInstruments.value.poll.channel)
        OuraLiveSource::class.java.getDeclaredMethod("suspendRawInstruments").apply { isAccessible = true }.invoke(source)
        source.rawInstrumentsIntent("raw-test", initial.sourceIncarnation, initial.poll.generation, "battery", 250, true)
        assertNull(source.rawInstruments.value.poll.channel)
        source.rawInstrumentsIntent("raw-test", initial.sourceIncarnation + 1, source.rawInstruments.value.poll.generation, "battery", 250, true)
        assertNull(source.rawInstruments.value.poll.channel)
        source.stop()
    }
    @Test fun packedResearchRecordsAreCapturedFromReassemblerAndFragmentsAreNeverGuessed() {
        ready()
        notify(0x81, 7, 3, 0, 0, 0, 0x80, 1, 2, 0x8b, 7, 4, 0, 0, 0, 3, 4, 5)
        assertEquals(listOf("event:81", "event:8b"), source.rawInstruments.value.capture.records.map { it.observation.channel })
        notify(0x81, 7, 5, 0, 0, 0, 0x80) // full declared outer body absent
        notify(1, 2) // continuation is not a self-contained record
        assertEquals(2, source.rawInstruments.value.capture.records.size)
        source.stop()
    }

    @Test fun authStatusOnSameConnectionDoesNotClearUncorrelatableTimeoutLatch() {
        ready()
        val poll = OuraLiveSource::class.java.getDeclaredField("rawPoll").apply { isAccessible = true }.get(source) as OuraRawPoll
        val queue = OuraWriteQueue(submit = { true }, onStarted = { poll.started(it) }, onCompleted = { poll.completed(it) })
        OuraLiveSource::class.java.getDeclaredField("commandQueue").apply { isAccessible = true }.set(source, queue)
        val initial = source.rawInstruments.value
        source.rawInstrumentsIntent("raw-test", initial.sourceIncarnation, initial.poll.generation, "battery", 250, true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(151))
        queue.complete(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(9000))
        assertEquals(OuraRawPollPhase.STALLED, source.rawInstruments.value.poll.phase)
        notify(0x2f, 2, 0x2e, 0)
        val current = source.rawInstruments.value
        source.rawInstrumentsIntent("raw-test", current.sourceIncarnation, current.poll.generation, "battery", 250, true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(151))
        assertEquals(OuraRawPollPhase.STALLED, source.rawInstruments.value.poll.phase)
        source.stop()
    }

}
