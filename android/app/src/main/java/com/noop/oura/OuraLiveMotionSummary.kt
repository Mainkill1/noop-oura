package com.noop.oura

/** One host-receipt second of uncalibrated signed16 XYZ; no ring timestamp is inferred. */
data class OuraLiveMotionSummary(val values: Map<String, Long>) {
    val ts: Long get() = values.getValue("first_receipt_ms") / 1000
    // Swift twin: `OuraLiveMotionSummary.mean`.
    fun mean(axis: String): Double = values.getValue("sum_$axis").toDouble() / values.getValue("count")
    // Swift twin: `OuraLiveMotionSummary.merge` in Packages/OuraProtocol/Sources/OuraProtocol/OuraLiveMotionSummary.swift.
    fun merge(other: OuraLiveMotionSummary): OuraLiveMotionSummary {
        validate(); other.validate()
        require(ts == other.ts) { "Summary seconds differ" }
        require(Math.addExact(values.getValue("count"), other.values.getValue("count")) <= Long.MAX_VALUE / 32768) { "Summary count exceeds arithmetic bound" }
        val first = if (values.getValue("first_receipt_ms") <= other.values.getValue("first_receipt_ms")) this else other
        val last = if (values.getValue("last_receipt_ms") >= other.values.getValue("last_receipt_ms")) this else other
        return OuraLiveMotionSummary(values.mapValues { (key, value) ->
            when {
                key == "count" || key.startsWith("sum_") -> Math.addExact(value, other.values.getValue(key))
                key.startsWith("min_") -> minOf(value, other.values.getValue(key))
                key.startsWith("max_") -> maxOf(value, other.values.getValue(key))
                key.startsWith("first_") -> first.values.getValue(key)
                else -> last.values.getValue(key)
            }
        }).also { it.validate() }
    }
    // Swift twin: `OuraLiveMotionSummary.payloadJSON`.
    fun payloadJSON(): String {
        validate()
        return (values.mapValues { it.value.toString() } + mapOf("provenance" to "\"host_receipt\"", "units" to "\"raw_signed16\""))
            .toSortedMap().entries.joinToString(prefix = "{", postfix = "}", separator = ",") { "\"${it.key}\":${it.value}" }
    }
    // Swift twin: `OuraLiveMotionSummary.validate`.
    internal fun validate() {
        require(values.keys == numericKeys) { "Invalid summary fields" }
        val count = values.getValue("count")
        // This is an arithmetic bound, not an assumed sensor sampling rate.
        require(count in 1..(Long.MAX_VALUE / 32768)) { "Invalid summary count" }
        val first = values.getValue("first_receipt_ms")
        val last = values.getValue("last_receipt_ms")
        require(first >= 0 && last >= first && first / 1000 == last / 1000 && (count > 1 || first == last)) { "Invalid summary receipt bounds" }
        for (axis in listOf("x", "y", "z")) {
            val low = values.getValue("min_$axis")
            val high = values.getValue("max_$axis")
            val sum = values.getValue("sum_$axis")
            require(low in -32768L..32767L && high in low..32767L) { "Invalid raw signed16 extrema" }
            if (count == 1L) require(low == high && sum == low) { "Invalid singleton summary" }
            else require(sum >= low * (count - 1) + high && sum <= high * (count - 1) + low) { "Summary sum cannot contain its extrema" }
        }
    }
    companion object {
        // Swift twin: `OuraLiveMotionSummary.numericKeys`.
        internal val numericKeys = setOf("count", "first_receipt_ms", "last_receipt_ms") +
            listOf("x", "y", "z").flatMap { axis -> listOf("sum_$axis", "min_$axis", "max_$axis") }
        const val EVENT_KIND = "OURA_LIVE_MOTION"
        // Swift twin: `OuraLiveMotionSummary.sample` in Packages/OuraProtocol/Sources/OuraProtocol/OuraLiveMotionSummary.swift.
        fun sample(epochMs: Long, xyz: OuraRawXYZ): OuraLiveMotionSummary = OuraLiveMotionSummary(buildMap {
            put("count", 1L); put("first_receipt_ms", epochMs); put("last_receipt_ms", epochMs)
            for ((axis, value) in listOf("x" to xyz.x, "y" to xyz.y, "z" to xyz.z))
                for (prefix in listOf("sum", "min", "max")) put("${prefix}_$axis", value.toLong())
        })
    }
}
