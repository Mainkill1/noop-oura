package com.noop.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** One serial reader per started screen; obsolete or cancelled reads cannot publish. */
internal suspend fun <T> refreshOuraWindow(
    lifecycle: Lifecycle, live: Boolean, expectedKey: String, currentKey: () -> String,
    prepare: suspend () -> Boolean, load: suspend () -> T, publish: (T) -> Unit,
    onError: (Exception) -> Unit,
) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        try {
            if (!prepare()) return@repeatOnLifecycle
            do {
                coroutineContext.ensureActive()
                if (currentKey() != expectedKey) return@repeatOnLifecycle
                try {
                    val result = load()
                    coroutineContext.ensureActive()
                    if (currentKey() != expectedKey) return@repeatOnLifecycle
                    publish(result)
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) {
                    coroutineContext.ensureActive()
                    if (currentKey() == expectedKey) onError(error)
                }
                if (live) delay(5000)
            } while (live)
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            coroutineContext.ensureActive()
            if (currentKey() == expectedKey) onError(error)
        }
    }
}
