package com.noop.ble

import com.noop.oura.OuraCommand
import com.noop.oura.OuraCommands
import com.noop.oura.OuraDriverPhase

/** Tracks view demand separately from an in-progress authentication handshake. */
internal class OuraLiveHRDemand {
    var armed = false
        private set
    var needsShutdown = false
        private set
    fun reset() { armed = false; needsShutdown = false }
    /** Restore automatic sampling and clear a previous process's notification subscription. */
    fun authenticated() {
        armed = true
        needsShutdown = true
    }
    fun update(wanted: Boolean, ready: Boolean): List<OuraCommand> {
        if (!ready || wanted == armed) return emptyList()
        armed = wanted
        needsShutdown = needsShutdown || wanted
        return if (wanted) listOf(OuraCommands.liveHREnable(), OuraCommands.liveHRSubscribe())
        else listOf(OuraCommands.liveHRRestoreAutomatic(), OuraCommands.liveHRUnsubscribe())
    }

    fun keepAlive(wanted: Boolean, phase: OuraDriverPhase): List<OuraCommand> {
        if (!wanted || phase !in setOf(OuraDriverPhase.Streaming, OuraDriverPhase.FetchingHistory)) return emptyList()
        armed = true
        needsShutdown = true
        return listOf(OuraCommands.liveHREnable(), OuraCommands.liveHRSubscribe())
    }
}
