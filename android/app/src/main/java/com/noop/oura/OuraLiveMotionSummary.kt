package com.noop.oura

/** One host-receipt second of uncalibrated signed16 XYZ; no ring timestamp is inferred. */
data class OuraLiveMotionSummary(val values: Map<String, Long>) {
    val ts: Long get() = values.getValue("first_receipt_ms") / 1000
    // Swift twin: `OuraLiveMotionSummary.mean`.
    fun mean(axis: String): Double = values.getValue("sum_$axis").toDouble() / values.getValue("count")
    // Swift twin: `OuraLiveMotionSummary.merge` in Packages/OuraProtocol/Sources/OuraProtocol/OuraLiveMotionSummary.swift.
    fun merge(other: OuraLiveMotionSummary): OuraLiveMotionSummary {
        require(ts == other.ts)
        val first = if (values.getValue("first_receipt_ms") <= other.values.getValue("first_receipt_ms")) this else other
        val last = if (values.getValue("last_receipt_ms") >= other.values.getValue("last_receipt_ms")) this else other
        return OuraLiveMotionSummary(values.mapValues { (key, value) ->
            when {
                key == "count" || key.startsWith("sum_") -> value + other.values.getValue(key)
                key.startsWith("min_") -> minOf(value, other.values.getValue(key))
                key.startsWith("max_") -> maxOf(value, other.values.getValue(key))
                key.startsWith("first_") -> first.values.getValue(key)
                else -> last.values.getValue(key)
            }
        })
    }
    // Swift twin: `OuraLiveMotionSummary.payloadJSON`.
    fun payloadJSON(): String = (values.mapValues { it.value.toString() } + mapOf("provenance" to "\"host_receipt\"", "units" to "\"raw_signed16\""))
        .toSortedMap().entries.joinToString(prefix = "{", postfix = "}", separator = ",") { "\"${it.key}\":${it.value}" }
    companion object {
        const val EVENT_KIND = "OURA_LIVE_MOTION"
        // Swift twin: `OuraLiveMotionSummary.sample` in Packages/OuraProtocol/Sources/OuraProtocol/OuraLiveMotionSummary.swift.
        fun sample(epochMs: Long, xyz: OuraRawXYZ): OuraLiveMotionSummary = OuraLiveMotionSummary(buildMap {
            put("count", 1L); put("first_receipt_ms", epochMs); put("last_receipt_ms", epochMs)
            for ((axis, value) in listOf("x" to xyz.x, "y" to xyz.y, "z" to xyz.z))
                for (prefix in listOf("sum", "min", "max")) put("${prefix}_$axis", value.toLong())
        })
    }
}
