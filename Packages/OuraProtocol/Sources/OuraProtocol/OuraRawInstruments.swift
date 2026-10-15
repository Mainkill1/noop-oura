import Foundation

// Independent Swift wire/numeric twin. Raw evidence is volatile; lifecycle/debug/control payloads
// never enter a capture. Sensor record IDs outside the dictionary keep their numeric byte fields.
public struct OuraRawChannel: Sendable {
    public let key: String
    public let number: String
    public let meaning: String
    public let acquisition: String
    public let verified: Bool
    public let supported: Bool
}
public struct OuraRawField: Sendable {
    public let key: String
    public let unit: String
    public let values: [Double]
}
public struct OuraRawObservation: Sendable {
    public let channel: String
    public let wire: [UInt8]
    public let ringTick: UInt32?
    public let fields: [OuraRawField]
    public let context: String
}
public struct OuraRawReceipt: Sendable {
    public let observation: OuraRawObservation
    public let receivedAtElapsedMs: Int64
}
public struct OuraRawSnapshot: Sendable {
    public let records: [OuraRawReceipt]
    public let evictedRecords: Int64
    public let evictedBytes: Int64
    public let evictedValues: Int64
    public let truncatedRecords: Int64
}
public struct OuraRawStatistics: Sendable {
    public let latest: Double
    public let minimum: Double
    public let maximum: Double
    public let mean: Double
    public let count: Int
}

public enum OuraRawInstruments {
    private static let denied: Set<UInt8> = [0x41, 0x42, 0x43, 0x45, 0x53, 0x85]
    private static let decoded: Set<UInt8> = [0x44, 0x46, 0x47, 0x5d, 0x60, 0x69, 0x75, 0x80]
    public static let catalog: [OuraRawChannel] = OuraEventTag.allCases.map { tag in
        let meaning: String
        switch tag.rawValue {
        case 0x80: meaning = "Daytime green IBI and quality"
        case 0x60: meaning = "Nighttime IBI and optical amplitudes"
        case 0x46: meaning = "Recorded temperature"
        case 0x47: meaning = "Recorded averaged motion"
        case 0x5d: meaning = "Recorded heart rate and RMSSD"
        default: meaning = tag.name
        }
        return OuraRawChannel(key: String(format: "event:%02x", tag.rawValue), number: String(format: "0x%02X", tag.rawValue),
            meaning: meaning, acquisition: "record", verified: decoded.contains(tag.rawValue), supported: !denied.contains(tag.rawValue))
    } + [
        OuraRawChannel(key: "event:8b", number: "0x8B", meaning: "Optical R-ratio and perfusion research", acquisition: "record", verified: false, supported: true),
        OuraRawChannel(key: "event:81", number: "0x81", meaning: "Raw optical PPG research", acquisition: "record", verified: false, supported: true),
        OuraRawChannel(key: "hr", number: "0x2F/0x28", meaning: "Live heart rate", acquisition: "push", verified: true, supported: true),
        OuraRawChannel(key: "motion", number: "0x33", meaning: "Live XYZ", acquisition: "push", verified: true, supported: true),
        OuraRawChannel(key: "battery", number: "0x0D", meaning: "Battery response", acquisition: "poll", verified: true, supported: true),
        OuraRawChannel(key: "status:02", number: "0x2F/0x21/0x02", meaning: "Daytime feature status", acquisition: "poll", verified: true, supported: true),
        OuraRawChannel(key: "status:04", number: "0x2F/0x21/0x04", meaning: "Oxygen feature status", acquisition: "poll", verified: true, supported: true),
        OuraRawChannel(key: "status:0b", number: "0x2F/0x21/0x0B", meaning: "Real-steps feature status", acquisition: "poll", verified: true, supported: true)]

    public static func capture(_ bytes: [UInt8]) -> [OuraRawObservation] {
        guard !bytes.isEmpty else { return [] }
        let frames = OuraFraming.parseOuterFrames(bytes)
        guard frames.reduce(0, { $0 + $1.totalLength }) == bytes.count else { return [] }
        return frames.compactMap { frame in
            let body = frame.body
            let wire = [frame.op, UInt8(body.count)] + body
            if frame.op >= 0x41 && !denied.contains(frame.op) {
                guard let records = OuraFraming.tiledRecords(wire), records.count == 1 else { return nil }
                let rec = records[0]
                return OuraRawObservation(channel: String(format: "event:%02x", rec.type), wire: wire, ringTick: rec.ringTimestamp,
                    fields: recordFields(rec), context: "record type/length/ring tick/payload; byte fields index payload")
            }
            if frame.op == 0x33 {
                let packet = OuraLiveMotion.decode(frame)
                var fields: [OuraRawField] = []
                if let packet {
                    fields = [OuraRawField(key: "x", unit: "live counts", values: packet.samples.map { Double($0.x) }),
                        OuraRawField(key: "y", unit: "live counts", values: packet.samples.map { Double($0.y) }),
                        OuraRawField(key: "z", unit: "live counts", values: packet.samples.map { Double($0.z) })]
                }
                return observation("motion", frame, fields, "outer op/length/metadata/sequence/XYZ; byte fields index body")
            }
            if frame.op == 0x0d {
                let fields = OuraDecoders.decodeBattery(body).map { [OuraRawField(key: "battery", unit: "%", values: [Double($0.percent)])] } ?? []
                return observation("battery", frame, fields, "outer op/length/battery body; byte fields index body")
            }
            if frame.op == 0x2f, body.count >= 2, body[0] == 0x28, body[1] == 2 {
                var fields: [OuraRawField] = []
                if let hr = OuraDecoders.decodeLiveHRPush(Array(body.dropFirst()), ringTimestamp: 0) {
                    fields = [OuraRawField(key: "hr", unit: "bpm", values: [Double(hr.bpm)]), OuraRawField(key: "ibi", unit: "ms", values: [Double(hr.ibiMs)])]
                }
                return observation("hr", frame, fields, "secure op/length/subop/HR body; no ring timestamp; byte fields index sub-body", skip: 1)
            }
            if frame.op == 0x2f, body.count >= 2, body[0] == 0x21, [2, 4, 11].contains(body[1]) {
                var fields: [OuraRawField] = []
                if let status = OuraDecoders.decodeFeatureStatus(Array(body.dropFirst())) {
                    fields = [("mode", status.mode), ("status", status.status), ("state", status.state), ("subscription", status.subscription)].map {
                        OuraRawField(key: $0.0, unit: "code", values: [Double($0.1)])
                    }
                }
                return observation(String(format: "status:%02x", body[1]), frame, fields,
                    "secure op/length/subop/feature/status; byte fields index sub-body", skip: 1)
            }
            return nil
        }
    }
    private static func observation(_ key: String, _ frame: OuraOuterFrame, _ fields: [OuraRawField], _ context: String, skip: Int = 0) -> OuraRawObservation {
        OuraRawObservation(channel: key, wire: [frame.op, UInt8(frame.body.count)] + frame.body, ringTick: nil,
            fields: fields + byteFields(Array(frame.body.dropFirst(skip))), context: context)
    }
    private static func byteFields(_ bytes: [UInt8]) -> [OuraRawField] {
        bytes.enumerated().map { OuraRawField(key: "byte[\($0.offset)]", unit: "byte", values: [Double($0.element)]) }
    }
    public static func recordFields(_ rec: OuraRecord) -> [OuraRawField] {
        let b = rec.payload.map(Int.init)
        var fields: [OuraRawField] = []
        func field(_ key: String, _ unit: String, _ values: [Double]) {
            if !values.isEmpty { fields.append(OuraRawField(key: key, unit: unit, values: values)) }
        }
        switch rec.type {
        case 0x80:
            let offsets = stride(from: 0, to: max(0, min(b.count - 1, 14)), by: 2)
            field("ibi", "ms", offsets.map { Double((b[$0] << 3) | (b[$0 + 1] & 7)) })
            field("quality", "bits", offsets.map { Double((b[$0 + 1] >> 3) & 3) })
        case 0x60, 0x44:
            if b.count >= 14 {
                let pack12 = b[12], pack13 = b[13]
                let high = [(pack12 >> 5) & 6, (pack12 >> 3) & 6, (pack12 >> 1) & 6, (pack12 & 3) << 1, (pack13 >> 5) & 6, (pack13 >> 3) & 6]
                field("ibi", "ms", (0..<6).map { Double((b[$0 + 6] & 1) | (b[$0] << 3) | high[$0]) })
                let shift = (pack13 & 15) == 7 ? 0 : (pack13 & 15) + 1
                field("amplitude", "optical counts", (0..<6).map { Double((b[$0 + 6] >> 1) << shift) })
            }
        case 0x5d:
            if b.count % 2 == 0 {
                field("hr", "bpm", stride(from: 0, to: b.count, by: 2).map { Double(b[$0]) })
                field("rmssd", "ms", stride(from: 1, to: b.count, by: 2).map { Double(b[$0]) })
            }
        case 0x46: field("temperature", "°C", OuraDecoders.decodeTemp(rec)?.map(\.celsius) ?? [])
        case 0x69: field("temperature", "°C", OuraDecoders.decodeTempPeriod(rec).map { [$0.celsius] } ?? [])
        case 0x75: field("temperature", "°C", OuraDecoders.decodeSleepTemp(rec)?.map(\.celsius) ?? [])
        case 0x47:
            if let m = OuraDecoders.decodeMotionEvents(rec) {
                field("x", "record counts ×8", [Double(m.avgX)]); field("y", "record counts ×8", [Double(m.avgY)])
                field("z", "record counts ×8", [Double(m.avgZ)]); field("motion", "s", [Double(m.motionSeconds)])
            }
        default: break
        }
        return fields + byteFields(rec.payload)
    }
    public static func readable(_ bytes: [UInt8]) -> String {
        bytes.map { (32...126).contains($0) && $0 != 92 ? String(UnicodeScalar($0)) : String(format: "\\x%02x", $0) }.joined()
    }
    public static func statistics(_ values: [Double]) -> OuraRawStatistics? {
        guard let last = values.last else { return nil }
        return OuraRawStatistics(latest: last, minimum: values.min()!, maximum: values.max()!, mean: values.reduce(0, +) / Double(values.count), count: values.count)
    }
}
public final class OuraRawBuffer {
    private let maxRecords: Int
    private let maxBytes: Int
    private let maxValues: Int
    private var records: [OuraRawReceipt] = []
    private var bytes = 0
    private var values = 0
    private var evictedRecords: Int64 = 0
    private var evictedBytes: Int64 = 0
    private var evictedValues: Int64 = 0
    private var truncatedRecords: Int64 = 0
    public init(maxRecords: Int = 256, maxBytes: Int = 65536, maxValues: Int = 32768) {
        self.maxRecords = maxRecords; self.maxBytes = maxBytes; self.maxValues = maxValues
    }
    public func append(_ observation: OuraRawObservation, receivedAtElapsedMs: Int64) {
        let count = observation.fields.reduce(0) { $0 + $1.values.count }
        guard observation.wire.count <= maxBytes, count <= maxValues, maxRecords > 0 else { truncatedRecords += 1; return }
        records.append(OuraRawReceipt(observation: observation, receivedAtElapsedMs: receivedAtElapsedMs))
        bytes += observation.wire.count; values += count
        while records.count > maxRecords || bytes > maxBytes || values > maxValues {
            let old = records.removeFirst()
            let removed = old.observation.fields.reduce(0) { $0 + $1.values.count }
            bytes -= old.observation.wire.count; values -= removed
            evictedRecords += 1; evictedBytes += Int64(old.observation.wire.count); evictedValues += Int64(removed)
        }
    }
    public func snapshot() -> OuraRawSnapshot {
        OuraRawSnapshot(records: records, evictedRecords: evictedRecords, evictedBytes: evictedBytes, evictedValues: evictedValues, truncatedRecords: truncatedRecords)
    }
}
