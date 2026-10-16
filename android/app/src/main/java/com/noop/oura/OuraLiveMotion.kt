package com.noop.oura

/** Raw live ACM counts. No g calibration or ring-time interpretation is applied. */
data class OuraRawXYZ(val x: Int, val y: Int, val z: Int)
data class OuraLiveMotionPacket(val metadataByte: Int, val sequence: Int, val samples: List<OuraRawXYZ>)

/** Documented response shape; see docs/OURA_LIVE_PROTOCOL.md for evidence and remaining uncertainty. */
object OuraLiveMotion {
    // Swift twin: `OuraLiveMotion.decode`.
    fun decode(frame: OuraOuterFrame): OuraLiveMotionPacket? {
        val b = frame.body
        if (frame.op != 0x33 || b.size !in setOf(8, 14) || b.any { it !in 0..255 }) return null
        // Swift twin: `OuraLiveMotion.signed16`.
        fun signed16(offset: Int): Int = (b[offset] or (b[offset + 1] shl 8)).toShort().toInt()
        return OuraLiveMotionPacket(b[0], b[1], (2 until b.size step 6).map { offset ->
            OuraRawXYZ(signed16(offset), signed16(offset + 2), signed16(offset + 4))
        })
    }
}
