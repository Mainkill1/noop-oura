package com.noop.oura

// Wire-only diagnostics. Swift twin: OuraRawInstruments.swift. The allowlist runs BEFORE any
// observable capture is created. Debug, lifecycle, time anchors and control bodies are excluded.
data class OuraRawChannel(val key: String, val number: String, val meaning: String,
    val acquisition: String, val verified: Boolean, val supported: Boolean = true)
data class OuraRawField(val key: String, val unit: String, val values: List<Double>)
data class OuraRawObservation(val channel: String, val wire: List<Int>, val ringTick: Long?,
    val fields: List<OuraRawField>, val context: String)
data class OuraRawReceipt(val observation: OuraRawObservation, val receivedAtElapsedMs: Long)
data class OuraRawSnapshot(val records: List<OuraRawReceipt> = emptyList(), val evictedRecords: Long = 0,
    val evictedBytes: Long = 0, val evictedValues: Long = 0, val truncatedRecords: Long = 0)
data class OuraRawStatistics(val latest: Double, val minimum: Double, val maximum: Double,
    val mean: Double, val count: Int)

object OuraRawInstruments {
    private val denied = setOf(0x41, 0x42, 0x43, 0x45, 0x53, 0x85)
    private val decoded = setOf(0x44, 0x46, 0x47, 0x5d, 0x60, 0x69, 0x75, 0x80)
    val catalog: List<OuraRawChannel> = OuraEventTag.entries.map { tag ->
        OuraRawChannel("event:%02x".format(tag.raw), "0x%02X".format(tag.raw), when (tag.raw) {
            0x80 -> "Daytime green IBI and quality"
            0x60 -> "Nighttime IBI and optical amplitudes"
            0x46 -> "Recorded temperature"
            0x47 -> "Recorded averaged motion"
            0x5d -> "Recorded heart rate and RMSSD"
            else -> tag.tagName
        }, "record", tag.raw in decoded, tag.raw !in denied)
    } + listOf(
        OuraRawChannel("event:8b", "0x8B", "Optical R-ratio and perfusion research", "record", false),
        OuraRawChannel("event:81", "0x81", "Raw optical PPG research", "record", false),
        OuraRawChannel("hr", "0x2F/0x28", "Live heart rate", "push", true),
        OuraRawChannel("motion", "0x33", "Live XYZ", "push", true),
        OuraRawChannel("battery", "0x0D", "Battery response", "poll", true),
        OuraRawChannel("status:02", "0x2F/0x21/0x02", "Daytime feature status", "poll", true),
        OuraRawChannel("status:04", "0x2F/0x21/0x04", "Oxygen feature status", "poll", true),
        OuraRawChannel("status:0b", "0x2F/0x21/0x0B", "Real-steps feature status", "poll", true))

    // No valid prefix is captured when a trailing outer frame is malformed. Oura has no outer CRC;
    // exact full-envelope tiling is the structural integrity boundary available on this protocol.
    // Swift twin: `OuraRawInstruments.capture`.
    fun capture(bytes: List<Int>): List<OuraRawObservation> {
        if (bytes.isEmpty() || bytes.any { it !in 0..255 }) return emptyList()
        val frames = OuraFraming.parseOuterFrames(bytes.toIntArray())
        if (frames.sumOf { it.totalLength } != bytes.size) return emptyList()
        return frames.mapNotNull { frame ->
            val b = frame.body
            when {
                frame.op >= 0x41 && frame.op !in denied -> {
                    val wire = listOf(frame.op, b.size) + b.toList()
                    val rec = OuraFraming.tiledRecords(wire.toIntArray())?.singleOrNull() ?: return@mapNotNull null
                    OuraRawObservation("event:%02x".format(rec.type), wire, rec.ringTimestamp,
                        recordFields(rec), "record type/length/ring tick/payload; byte fields index payload")
                }
                frame.op == 0x33 -> {
                    val packet = OuraLiveMotion.decode(frame)
                    val fields = if (packet == null) emptyList() else listOf(
                        OuraRawField("x", "live counts", packet.samples.map { it.x.toDouble() }),
                        OuraRawField("y", "live counts", packet.samples.map { it.y.toDouble() }),
                        OuraRawField("z", "live counts", packet.samples.map { it.z.toDouble() }))
                    observation("motion", frame, fields, "outer op/length/metadata/sequence/XYZ; byte fields index body")
                }
                frame.op == 0x0d -> {
                    val battery = OuraDecoders.decodeBattery(b)
                    observation("battery", frame, battery?.let { listOf(OuraRawField("battery", "%", listOf(it.percent.toDouble()))) }.orEmpty(),
                        "outer op/length/battery body; byte fields index body")
                }
                frame.op == 0x2f && b.size >= 2 && b[0] == 0x28 && b[1] == 2 -> {
                    val hr = OuraDecoders.decodeLiveHRPush(b.drop(1).toIntArray(), 0)
                    observation("hr", frame, hr?.let { listOf(OuraRawField("hr", "bpm", listOf(it.bpm.toDouble())),
                        OuraRawField("ibi", "ms", listOf(it.ibiMs.toDouble()))) }.orEmpty(), "secure op/length/subop/HR body; no ring timestamp; byte fields index sub-body", 1)
                }
                frame.op == 0x2f && b.size >= 2 && b[0] == 0x21 && b[1] in setOf(2, 4, 11) -> {
                    val status = OuraDecoders.decodeFeatureStatus(b.drop(1).toIntArray())
                    val fields = status?.let { listOf("mode" to it.mode, "status" to it.status, "state" to it.state,
                        "subscription" to it.subscription).map { (key, value) -> OuraRawField(key, "code", listOf(value.toDouble())) } }.orEmpty()
                    observation("status:%02x".format(b[1]), frame, fields, "secure op/length/subop/feature/status; byte fields index sub-body", 1)
                }
                else -> null
            }
        }
    }

    // Swift twin: `OuraRawInstruments.observation`.

    private fun observation(key: String, frame: OuraOuterFrame, fields: List<OuraRawField>, context: String,
        skip: Int = 0) = OuraRawObservation(key, listOf(frame.op, frame.body.size) + frame.body.toList(), null,
            fields + byteFields(frame.body.drop(skip)), context)

    // Swift twin: `OuraRawInstruments.byteFields`.

    private fun byteFields(bytes: List<Int>) = bytes.mapIndexed { index, value -> OuraRawField("byte[$index]", "byte", listOf(value.toDouble())) }

    // Swift twin: `OuraRawInstruments.recordFields`.

    fun recordFields(rec: OuraRecord): List<OuraRawField> {
        val b = rec.payload
        val fields = ArrayList<OuraRawField>()
        // Swift twin: `OuraRawInstruments.field`.
        fun field(key: String, unit: String, values: List<Double>) { if (values.isNotEmpty()) fields.add(OuraRawField(key, unit, values)) }
        when (rec.type) {
            0x80 -> {
                val pairs = (0 until minOf(b.size - 1, 14) step 2).toList()
                field("ibi", "ms", pairs.map { ((b[it] shl 3) or (b[it + 1] and 7)).toDouble() })
                field("quality", "bits", pairs.map { ((b[it + 1] shr 3) and 3).toDouble() })
            }
            0x60, 0x44 -> if (b.size >= 14) {
                val shifts = listOf(5, 3, 1, -1, 5, 3)
                val intervals = (0..5).map { k ->
                    val high = if (k == 3) (b[12] and 3) shl 1 else ((b[if (k < 4) 12 else 13] shr shifts[k]) and 6)
                    ((b[k + 6] and 1) or (b[k] shl 3) or high).toDouble()
                }
                val n = b[13] and 15; val shift = if (n == 7) 0 else n + 1
                field("ibi", "ms", intervals)
                field("amplitude", "optical counts", (0..5).map { ((b[it + 6] shr 1) shl shift).toDouble() })
            }
            0x5d -> if (b.size % 2 == 0) {
                field("hr", "bpm", b.indices.filter { it % 2 == 0 }.map { b[it].toDouble() })
                field("rmssd", "ms", b.indices.filter { it % 2 == 1 }.map { b[it].toDouble() })
            }
            0x46 -> field("temperature", "°C", OuraDecoders.decodeTemp(rec).orEmpty().map { it.celsius })
            0x69 -> field("temperature", "°C", listOfNotNull(OuraDecoders.decodeTempPeriod(rec)?.celsius))
            0x75 -> field("temperature", "°C", OuraDecoders.decodeSleepTemp(rec).orEmpty().map { it.celsius })
            0x47 -> OuraDecoders.decodeMotionEvents(rec)?.let { motion ->
                field("x", "record counts ×8", listOf(motion.avgX.toDouble()))
                field("y", "record counts ×8", listOf(motion.avgY.toDouble()))
                field("z", "record counts ×8", listOf(motion.avgZ.toDouble()))
                field("motion", "s", listOf(motion.motionSeconds.toDouble()))
            }
        }
        return fields + byteFields(b.toList())
    }
    // Swift twin: `OuraRawInstruments.readable`.
    fun readable(bytes: List<Int>): String = bytes.joinToString("") {
        if (it in 32..126 && it != 92) it.toChar().toString() else "\\x%02x".format(it)
    }
    // Swift twin: `OuraRawInstruments.statistics`.
    fun statistics(values: List<Double>): OuraRawStatistics? = if (values.isEmpty()) null else
        OuraRawStatistics(values.last(), values.min(), values.max(), values.sum() / values.size, values.size)
}

// Bounded volatile evidence; never a database or a log sink. Evictions count every discarded value.
class OuraRawBuffer(private val maxRecords: Int = 256, private val maxBytes: Int = 65536,
    private val maxValues: Int = 32768) {
    private val records = java.util.ArrayDeque<OuraRawReceipt>()
    private var bytes = 0
    private var values = 0
    private var evictedRecords = 0L
    private var evictedBytes = 0L
    private var evictedValues = 0L
    private var truncatedRecords = 0L
    // Swift twin: `OuraRawBuffer.append`.
    fun append(observation: OuraRawObservation, receivedAtElapsedMs: Long) {
        val count = observation.fields.sumOf { it.values.size }
        if (observation.wire.size > maxBytes || count > maxValues || maxRecords <= 0) {
            truncatedRecords++; return
        }
        records.addLast(OuraRawReceipt(observation, receivedAtElapsedMs))
        bytes += observation.wire.size; values += count
        while (records.size > maxRecords || bytes > maxBytes || values > maxValues) {
            val old = records.removeFirst(); val oldValues = old.observation.fields.sumOf { it.values.size }
            bytes -= old.observation.wire.size; values -= oldValues
            evictedRecords++; evictedBytes += old.observation.wire.size; evictedValues += oldValues
        }
    }
    // Swift twin: `OuraRawBuffer.snapshot`.
    fun snapshot() = OuraRawSnapshot(records.toList(), evictedRecords, evictedBytes, evictedValues, truncatedRecords)
}
