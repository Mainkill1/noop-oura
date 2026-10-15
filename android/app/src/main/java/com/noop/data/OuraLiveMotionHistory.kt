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
        // Swift twin: `OuraLiveMotionSummary.field`.
        private val field = """"([a-z_]+)"[ \t\r\n]*:[ \t\r\n]*("(?:host_receipt|raw_signed16)"|-?(?:0|[1-9][0-9]*))"""
        // Swift twin: `OuraLiveMotionSummary.space`.
        private val space = """[ \t\r\n]*"""
        // Swift twin: `OuraLiveMotionSummary.fieldPattern`.
        private val fieldPattern = Regex(field)
        // Swift twin: `OuraLiveMotionSummary.payloadShape`.
        private val payloadShape = Regex(space + "\\{" + space + field + space + "(?:," + space + field + space + ")*\\}" + space)
        // Swift twin: `OuraLiveMotionSummary.parse`.
        fun parse(ts: Long, payload: String): OuraLiveMotionHistory? = runCatching {
            val json = JSONObject(payload)
            require(json.get("provenance") == "host_receipt" && json.get("units") == "raw_signed16")
            val keys = OuraLiveMotionSummary.numericKeys
            require(json.keys().asSequence().toSet() == keys + setOf("provenance", "units"))
            // Check original tokens rather than normalized numeric values, matching the Swift boundary.
            require(payloadShape.matches(payload))
            val fields = fieldPattern.findAll(payload).toList()
            require(fields.size == json.length())
            val tokens = fields.associate { it.groupValues[1] to it.groupValues[2] }
            val summary = OuraLiveMotionSummary(keys.associateWith {
                val value = json.get(it)
                require(value is Number) { "Summary fields must be JSON integers" }
                requireNotNull(tokens.getValue(it).toLongOrNull()) { "Summary fields must be signed64 integer tokens" }
            })
            summary.validate()
            require(summary.ts == ts)
            OuraLiveMotionHistory(ts, summary)
        }.getOrNull()
    }
}
