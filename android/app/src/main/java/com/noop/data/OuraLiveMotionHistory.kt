package com.noop.data

import com.noop.oura.OuraLiveMotionSummary
import org.json.JSONObject

/** Host-captured raw signed16 counts, separate from ring-banked signed-byte ×8 movement. */
data class OuraLiveMotionHistory(val ts: Long, val summary: OuraLiveMotionSummary) {
    val count: Long get() = summary.values.getValue("count")
    val firstReceiptEpochMs: Long get() = summary.values.getValue("first_receipt_ms")
    val lastReceiptEpochMs: Long get() = summary.values.getValue("last_receipt_ms")
    val provenance: String get() = "host_receipt"
    fun mean(axis: String): Double = summary.mean(axis)
    fun min(axis: String): Long = summary.values.getValue("min_$axis")
    fun max(axis: String): Long = summary.values.getValue("max_$axis")
    companion object {
        // Swift twin: `OuraLiveMotionSummary.parse`.
        fun parse(ts: Long, payload: String): OuraLiveMotionHistory? = runCatching {
            val json = JSONObject(payload)
            require(json.getString("provenance") == "host_receipt" && json.getString("units") == "raw_signed16")
            val keys = OuraLiveMotionSummary.sample(0, com.noop.oura.OuraRawXYZ(0, 0, 0)).values.keys
            val summary = OuraLiveMotionSummary(keys.associateWith { json.getLong(it) })
            require(summary.ts == ts && summary.values.getValue("count") > 0 && summary.values.getValue("last_receipt_ms") / 1000 == ts)
            OuraLiveMotionHistory(ts, summary)
        }.getOrNull()
    }
}
