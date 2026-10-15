import Foundation

/// One host-receipt second of raw signed16 XYZ; no ring timestamp or calibration is inferred.
public struct OuraLiveMotionSummary: Equatable, Sendable {
    public static let eventKind = "OURA_LIVE_MOTION"
    public let values: [String: Int64]
    public init(values: [String: Int64]) { self.values = values }
    public var ts: Int64 { values["first_receipt_ms"]! / 1000 }
    // Kotlin twin: `OuraLiveMotionSummary.mean`.
    public func mean(_ axis: String) -> Double { Double(values["sum_\(axis)"]!) / Double(values["count"]!) }
    // Kotlin twin: `OuraLiveMotionSummary.sample` in android/app/src/main/java/com/noop/oura/OuraLiveMotionSummary.kt.
    public static func sample(epochMs: Int64, xyz: OuraRawXYZ) -> OuraLiveMotionSummary {
        var values: [String: Int64] = ["count": 1, "first_receipt_ms": epochMs, "last_receipt_ms": epochMs]
        for (axis, value) in [("x", xyz.x), ("y", xyz.y), ("z", xyz.z)] {
            for prefix in ["sum", "min", "max"] { values["\(prefix)_\(axis)"] = Int64(value) }
        }
        return OuraLiveMotionSummary(values: values)
    }
    // Kotlin twin: `OuraLiveMotionSummary.merge` in android/app/src/main/java/com/noop/oura/OuraLiveMotionSummary.kt.
    public func merge(_ other: OuraLiveMotionSummary) -> OuraLiveMotionSummary {
        precondition(ts == other.ts)
        let first = values["first_receipt_ms"]! <= other.values["first_receipt_ms"]! ? self : other
        let last = values["last_receipt_ms"]! >= other.values["last_receipt_ms"]! ? self : other
        return OuraLiveMotionSummary(values: values.reduce(into: [:]) { out, pair in
            let (key, value) = pair
            if key == "count" || key.hasPrefix("sum_") { out[key] = value + other.values[key]! }
            else if key.hasPrefix("min_") { out[key] = min(value, other.values[key]!) }
            else if key.hasPrefix("max_") { out[key] = max(value, other.values[key]!) }
            else if key.hasPrefix("first_") { out[key] = first.values[key]! }
            else { out[key] = last.values[key]! }
        })
    }
    // Kotlin twin: `OuraLiveMotionSummary.payloadJSON`.
    public func payloadJSON() throws -> String {
        var object: [String: Any] = values.mapValues { $0 as Any }
        object["provenance"] = "host_receipt"; object["units"] = "raw_signed16"
        return String(decoding: try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]), as: UTF8.self)
    }
    // Kotlin twin: `OuraLiveMotionHistory.parse`.
    public static func parse(ts: Int64, payload: String) throws -> OuraLiveMotionSummary {
        guard let object = try JSONSerialization.jsonObject(with: Data(payload.utf8)) as? [String: Any],
              object["provenance"] as? String == "host_receipt", object["units"] as? String == "raw_signed16" else { throw InvalidPayload.invalid }
        let keys = sample(epochMs: 0, xyz: OuraRawXYZ(x: 0, y: 0, z: 0)).values.keys
        var values: [String: Int64] = [:]
        for key in keys {
            guard let number = object[key] as? NSNumber else { throw InvalidPayload.invalid }
            values[key] = number.int64Value
        }
        let summary = OuraLiveMotionSummary(values: values)
        guard summary.ts == ts, values["count"]! > 0, values["last_receipt_ms"]! / 1000 == ts else { throw InvalidPayload.invalid }
        return summary
    }
    private enum InvalidPayload: Error { case invalid }
}
