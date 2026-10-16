package com.noop.ui

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.noop.ble.OuraRawState

internal data class OuraRawIntent(val sourceId: String, val incarnation: Long, val generation: Long,
    val channel: String?, val intervalMs: Long, val foreground: Boolean)

// The source accepts the entry token as an alias only until exit. Later callbacks cannot reacquire it.
internal class OuraRawOwner(private val emit: (OuraRawIntent) -> Unit) {
    private data class Token(val sourceId: String?, val incarnation: Long, val generation: Long)
    private fun token(snapshot: OuraRawState) = Token(snapshot.sourceDeviceId, snapshot.sourceIncarnation, snapshot.poll.generation)
    private var owned: OuraRawIntent? = null
    private var accepted: Token? = null
    private var observed: Token? = null
    private var stopped: Token? = null
    fun present(snapshot: OuraRawState, sourceId: String, visible: Boolean, ready: Boolean, channel: String, interval: Long) {
        val current = token(snapshot)
        observed = current
        if (!visible || !ready || snapshot.sourceDeviceId != sourceId) { hide(); return }
        val previous = owned
        if (previous != null && (previous.sourceId != sourceId || previous.incarnation != snapshot.sourceIncarnation)) hide()
        val existing = owned
        if (existing != null) {
            if (snapshot.poll.channel != null) accepted = current
            else if (current.generation != existing.generation && current != accepted) {
                // An authoritative reset can conflate a disconnect/reconnect while readiness stays true.
                owned = null; accepted = null
            }
        }
        if (owned == null) {
            // Stop is asynchronous. Re-entry using its old token would be rejected and strand the UI.
            if (snapshot.poll.channel != null || stopped == current) return
            val next = OuraRawIntent(sourceId, snapshot.sourceIncarnation, snapshot.poll.generation, channel, interval, true)
            owned = next; accepted = null; emit(next)
        } else {
            val next = owned!!.copy(channel = channel, intervalMs = interval)
            if (next != owned) { owned = next; emit(next) }
        }
    }
    fun hide() {
        val old = owned ?: return
        stopped = observed?.takeIf { it.sourceId == old.sourceId && it.incarnation == old.incarnation }
            ?: Token(old.sourceId, old.incarnation, old.generation)
        owned = null; accepted = null
        emit(old.copy(channel = null, foreground = false))
    }
}

@Composable
internal fun OuraRawLifecycle(owner: OuraRawOwner, lifecycle: Lifecycle, snapshot: OuraRawState,
    sourceId: String, visible: Boolean, ready: Boolean, channel: String, intervalMs: Long) {
    val current by rememberUpdatedState { owner.present(snapshot, sourceId, visible, ready, channel, intervalMs) }
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner, lifecycle, sourceId, visible, snapshot.sourceIncarnation) {
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_RESUME -> { resumed = true; current() }
            Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> { resumed = false; owner.hide() }
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); owner.hide() }
    }
    SideEffect { owner.present(snapshot, sourceId, visible && resumed, ready, channel, intervalMs) }
}
