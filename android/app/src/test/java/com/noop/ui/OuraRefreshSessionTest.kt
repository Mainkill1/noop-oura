package com.noop.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OuraRefreshSessionTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    @Test fun timestampLabelsRespectExplicitClockPreference() {
        val instant = java.time.Instant.parse("2026-10-14T17:06:07Z")
        val zone = java.time.ZoneId.of("UTC")
        assertTrue(ouraTimestampFormatter(true, true, java.util.Locale.US, zone).format(instant).endsWith("17:06:07"))
        assertTrue(ouraTimestampFormatter(false, false, java.util.Locale.US, zone).format(instant).endsWith("5:06 PM"))
    }

    @Test fun pollingStopsWhenHiddenAndRefreshesImmediatelyOnResume() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = Owner()
        val results = mutableListOf<Int>()
        var reads = 0
        val job = launch { refreshOuraWindow(owner.lifecycle, true, "ring", { "ring" }, { true },
            { ++reads }, { results += it }, { throw it }) }
        try {
            runCurrent()
            assertEquals(0, reads)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertEquals(listOf(1), results)
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            runCurrent()
            advanceTimeBy(20_000); runCurrent()
            assertEquals(1, reads)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertEquals(listOf(1, 2), results)
        } finally { job.cancel(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun slowReadsNeverOverlapAndPollDelayStartsAfterCompletion() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = Owner().apply { lifecycle.currentState = Lifecycle.State.STARTED }
        var active = 0
        var maxActive = 0
        val starts = mutableListOf<Long>()
        val job = launch { refreshOuraWindow(owner.lifecycle, true, "ring", { "ring" }, { true }, {
            starts += currentTime; active++; maxActive = maxOf(maxActive, active)
            delay(12_000); active--; starts.size
        }, {}, { throw it }) }
        try {
            runCurrent(); advanceTimeBy(20_000); runCurrent()
            assertEquals(listOf(0L, 17_000L), starts)
            assertEquals(1, maxActive)
        } finally { job.cancel(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun obsoleteSourceCannotPublishEvenIfItsReadIgnoresCancellation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = Owner().apply { lifecycle.currentState = Lifecycle.State.STARTED }
        val release = CompletableDeferred<Unit>()
        val output = mutableListOf<String>()
        var current = "old"
        val old = launch { refreshOuraWindow(owner.lifecycle, false, "old", { current }, { true }, {
            withContext(NonCancellable) { release.await() }; "old data"
        }, { output += it }, { throw it }) }
        var replacement: Job? = null
        try {
            runCurrent()
            current = "new"
            old.cancel()
            replacement = launch { refreshOuraWindow(owner.lifecycle, false, "new", { current }, { true },
                { "new data" }, { output += it }, { throw it }) }
            runCurrent()
            release.complete(Unit); runCurrent()
            assertEquals(listOf("new data"), output)
        } finally { release.complete(Unit); old.cancel(); replacement?.cancel(); runCurrent(); Dispatchers.resetMain() }
    }
}
