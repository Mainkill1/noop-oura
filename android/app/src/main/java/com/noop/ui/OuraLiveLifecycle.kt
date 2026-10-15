package com.noop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** A pending Start is owned immediately, so leave/background cancellation uses its accepted alias. */
@Composable
internal fun OuraLiveLifecycle(owner: OuraLiveOwner, lifecycle: Lifecycle, sourceId: String, live: Boolean) {
    DisposableEffect(owner, lifecycle, sourceId, live) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) owner.stop()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); owner.stop() }
    }
}
