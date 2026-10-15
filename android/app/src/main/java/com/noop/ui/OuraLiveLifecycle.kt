package com.noop.ui

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.noop.ble.OuraLiveSessionSnapshot
import com.noop.ble.OuraLiveSelection

/** One visible-page owner; sample/generation publications never create retry sessions. */
@Composable
internal fun OuraLiveLifecycle(owner: OuraLiveOwner, lifecycle: Lifecycle, sourceId: String, live: Boolean,
    snapshot: OuraLiveSessionSnapshot = OuraLiveSessionSnapshot(), ready: Boolean = false,
    selection: OuraLiveSelection = OuraLiveSelection()) {
    val currentSnapshot by rememberUpdatedState(snapshot)
    val currentReady by rememberUpdatedState(ready)
    val currentSelection by rememberUpdatedState(selection)
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner, lifecycle, sourceId, live) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> { resumed = false; owner.hide() }
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    owner.present(currentSnapshot, sourceId, live, currentReady, currentSelection)
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); owner.hide() }
    }
    SideEffect { owner.present(snapshot, sourceId, live && resumed, ready, selection) }
}
