package com.noop.ble

/** Host-only transfer diagnostics. Traffic counts include control replies, not unique stored samples. */
data class OuraHistoryTransferSnapshot(
    val sourceDeviceId: String = "",
    val startedAtMs: Long? = null,
    val endedAtMs: Long? = null,
    val elapsedMs: Long = 0,
    val outcome: OuraHistoryTransferOutcome? = null,
    val boostRequested: Boolean = false,
    val priorityRequestAccepted: Boolean? = null,
    val balancedRequestAccepted: Boolean? = null,
    val phyRestoreSubmitted: Boolean? = null,
    val mtu: Int = 23,
    val txPhy: Int? = null,
    val rxPhy: Int? = null,
    val notifications: Long = 0,
    val wireBytes: Long = 0,
    val maxNotificationBytes: Int = 0,
    val notificationSizes: List<Long> = listOf(0, 0, 0),
    val outerFrames: Long = 0,
    val packedNotifications: Long = 0,
    val records: Long = 0,
    val decodedEvents: Long = 0,
)

enum class OuraHistoryTransferOutcome { ACTIVE, COMPLETE, YIELDED, STOPPED, DISCONNECTED }

/** One connection owns its baseline and preference requests; callbacks never renew an ended boost.
 * Android-specific host policy; no ring command, stored value or physiological calculation changes.
 */
internal class OuraHistoryTransport(
    private val sourceId: String,
    private val supports2M: Boolean,
    private val link: Link,
) {
    interface Link {
        fun priority(high: Boolean): Boolean
        fun readPhy(): Boolean
        /** Arguments use the standard PHY values 1=1M, 2=2M, 3=coded, not Android bit masks. */
        fun preferPhy(tx: Int, rx: Int): Boolean
    }
    private var state = OuraHistoryTransferSnapshot(sourceDeviceId = sourceId)
    private var startedElapsedMs = 0L
    private var baseline: Pair<Int, Int>? = null
    private var readAttempted = false
    private var readPending = false
    private var restorePhy = false
    private val active get() = state.outcome == OuraHistoryTransferOutcome.ACTIVE

    fun begin(elapsedMs: Long, wallMs: Long) {
        if (active) return
        startedElapsedMs = elapsedMs
        state = OuraHistoryTransferSnapshot(sourceId, startedAtMs = wallMs,
            outcome = OuraHistoryTransferOutcome.ACTIVE, boostRequested = true,
            priorityRequestAccepted = link.priority(true), mtu = state.mtu, txPhy = state.txPhy, rxPhy = state.rxPhy)
        if (!readAttempted) {
            readAttempted = true
            readPending = link.readPhy()
        }
        requestFasterPhy()
    }

    fun phyRead(tx: Int, rx: Int, success: Boolean) {
        // An unsolicited/duplicate read must not overwrite the connection's original baseline.
        if (!readPending) return
        readPending = false
        if (success && tx in 1..3 && rx in 1..3) {
            baseline = tx to rx
            state = state.copy(txPhy = tx, rxPhy = rx)
            requestFasterPhy()
        }
    }

    fun phyUpdated(tx: Int, rx: Int, success: Boolean) {
        if (success && tx in 1..3 && rx in 1..3) state = state.copy(txPhy = tx, rxPhy = rx)
    }

    private fun requestFasterPhy() {
        val original = baseline ?: return
        if (!active || !state.boostRequested || !supports2M || restorePhy ||
            original.first !in 1..2 || original.second !in 1..2 || original == (2 to 2)) return
        // Restoration is based on submitting a preference, never on an update callback's timing.
        restorePhy = link.preferPhy(2, 2)
    }

    fun mtu(value: Int, success: Boolean) {
        if (success && value >= 23) state = state.copy(mtu = value)
    }

    fun notification(bytes: Int, completeOuterFrames: Int) {
        if (!active || bytes < 0) return
        val sizes = state.notificationSizes.toMutableList()
        sizes[if (bytes <= 20) 0 else if (bytes <= 100) 1 else 2]++
        state = state.copy(notifications = state.notifications + 1, wireBytes = state.wireBytes + bytes,
            maxNotificationBytes = maxOf(state.maxNotificationBytes, bytes), notificationSizes = sizes,
            outerFrames = state.outerFrames + completeOuterFrames.coerceAtLeast(0),
            packedNotifications = state.packedNotifications + if (completeOuterFrames > 1) 1 else 0)
    }

    fun records(envelopes: Int, decodedEvents: Int) {
        if (active) state = state.copy(records = state.records + envelopes.coerceAtLeast(0),
            decodedEvents = state.decodedEvents + decodedEvents.coerceAtLeast(0))
    }

    fun expireBoost(elapsedMs: Long): Boolean {
        if (!active || !state.boostRequested || elapsedMs - startedElapsedMs < BOOST_LEASE_MS) return false
        releasePreferences()
        return true
    }

    fun finish(outcome: OuraHistoryTransferOutcome, elapsedMs: Long, wallMs: Long, connected: Boolean = true) {
        if (!active || outcome == OuraHistoryTransferOutcome.ACTIVE) return
        if (connected) releasePreferences() else { restorePhy = false; state = state.copy(boostRequested = false) }
        state = state.copy(outcome = outcome, endedAtMs = wallMs, elapsedMs = (elapsedMs - startedElapsedMs).coerceAtLeast(0))
    }

    private fun releasePreferences() {
        if (!state.boostRequested) return
        val balanced = link.priority(false)
        val restored = if (restorePhy) baseline?.let { link.preferPhy(it.first, it.second) } else null
        restorePhy = false
        state = state.copy(boostRequested = false, balancedRequestAccepted = balanced, phyRestoreSubmitted = restored)
    }

    fun snapshot(elapsedMs: Long): OuraHistoryTransferSnapshot =
        if (active) state.copy(elapsedMs = (elapsedMs - startedElapsedMs).coerceAtLeast(0)) else state

    companion object { const val BOOST_LEASE_MS = 300_000L }
}
