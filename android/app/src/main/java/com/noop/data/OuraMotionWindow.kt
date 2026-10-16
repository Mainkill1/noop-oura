package com.noop.data

import org.json.JSONObject

/** Existing movement-gated ring summary; axes are raw signed-byte ×8 values, not calibrated g. */
data class OuraMotionWindow(
    val ts: Long, val seconds: Double, val x: Double?, val y: Double?, val z: Double?,
    val low: Double?, val high: Double?, val orientation: Int?,
) {
    fun values(): Map<String, Double> = buildMap {
        put("movement", seconds)
        x?.let { put("motion_x", it) }; y?.let { put("motion_y", it) }; z?.let { put("motion_z", it) }
        low?.let { put("motion_low", it) }; high?.let { put("motion_high", it) }
    }
    companion object {
        fun parse(ts: Long, payload: String): OuraMotionWindow? = runCatching {
            val json = JSONObject(payload)
            fun number(key: String): Double? = json.optDouble(key, Double.NaN).takeIf { it.isFinite() }
            fun integer(key: String, range: ClosedFloatingPointRange<Double>) = number(key)?.takeIf { it in range && it % 1.0 == 0.0 }
            fun axis(key: String) = number(key)?.takeIf { it in -1024.0..1016.0 && it % 8.0 == 0.0 }
            val seconds = integer("motion_seconds", 0.0..31.0) ?: return null
            val orientation = number("orientation")?.takeIf { it in 0.0..7.0 && it % 1.0 == 0.0 }?.toInt()
            OuraMotionWindow(ts, seconds, axis("x"), axis("y"), axis("z"),
                integer("low_intensity", 0.0..63.0),
                integer("high_intensity", 0.0..63.0), orientation)
        }.getOrNull()
    }
}
