import Foundation
import CoreFoundation

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
    public func merge(_ other: OuraLiveMotionSummary) throws -> OuraLiveMotionSummary {
        try validate(); try other.validate()
        guard ts == other.ts else { throw InvalidPayload.invalid }
        let (count, overflow) = values["count"]!.addingReportingOverflow(other.values["count"]!)
        guard !overflow, count <= Int64.max / 32768 else { throw InvalidPayload.invalid }
        let first = values["first_receipt_ms"]! <= other.values["first_receipt_ms"]! ? self : other
        let last = values["last_receipt_ms"]! >= other.values["last_receipt_ms"]! ? self : other
        let merged = OuraLiveMotionSummary(values: try values.reduce(into: [:]) { out, pair in
            let (key, value) = pair
            if key == "count" || key.hasPrefix("sum_") {
                let (sum, overflow) = value.addingReportingOverflow(other.values[key]!)
                guard !overflow else { throw InvalidPayload.invalid }
                out[key] = sum
            }
            else if key.hasPrefix("min_") { out[key] = min(value, other.values[key]!) }
            else if key.hasPrefix("max_") { out[key] = max(value, other.values[key]!) }
            else if key.hasPrefix("first_") { out[key] = first.values[key]! }
            else { out[key] = last.values[key]! }
        })
        try merged.validate()
        return merged
    }
    // Kotlin twin: `OuraLiveMotionSummary.payloadJSON`.
    public func payloadJSON() throws -> String {
        try validate()
        var object: [String: Any] = values.mapValues { $0 as Any }
        object["provenance"] = "host_receipt"; object["units"] = "raw_signed16"
        return String(decoding: try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]), as: UTF8.self)
    }
    // Kotlin twin: `OuraLiveMotionHistory.parse`.
    public static func parse(ts: Int64, payload: String) throws -> OuraLiveMotionSummary {
        guard let object = try JSONSerialization.jsonObject(with: Data(payload.utf8)) as? [String: Any],
              object["provenance"] as? String == "host_receipt", object["units"] as? String == "raw_signed16" else { throw InvalidPayload.invalid }
        let keys = numericKeys
        guard Set(object.keys) == keys.union(["provenance", "units"]) else { throw InvalidPayload.invalid }
        // Inspect original integer tokens: Foundation may store a large negative integer in a
        // decimal NSNumber, while normalizing some decimal literals to integral values.
        let range = NSRange(payload.startIndex..., in: payload)
        guard payloadShape.firstMatch(in: payload, range: range)?.range == range else { throw InvalidPayload.invalid }
        let fields = fieldPattern.matches(in: payload, range: range)
        guard fields.count == object.count else { throw InvalidPayload.invalid }
        var tokens: [String: String] = [:]
        for field in fields {
            tokens[String(payload[Range(field.range(at: 1), in: payload)!])] = String(payload[Range(field.range(at: 2), in: payload)!])
        }
        var values: [String: Int64] = [:]
        for key in keys {
            guard let number = object[key] as? NSNumber,
                  CFGetTypeID(number) != CFBooleanGetTypeID(),
                  let token = tokens[key], let integer = Int64(token) else { throw InvalidPayload.invalid }
            values[key] = integer
        }
        let summary = OuraLiveMotionSummary(values: values)
        try summary.validate()
        guard summary.ts == ts else { throw InvalidPayload.invalid }
        return summary
    }
    // Kotlin twin: `OuraLiveMotionHistory.field`.
    private static let field = #""([a-z_]+)"[ \t\r\n]*:[ \t\r\n]*("(?:host_receipt|raw_signed16)"|-?(?:0|[1-9][0-9]*))"#
    // Kotlin twin: `OuraLiveMotionHistory.space`.
    private static let space = #"[ \t\r\n]*"#
    // Kotlin twin: `OuraLiveMotionHistory.fieldPattern`.
    private static let fieldPattern = try! NSRegularExpression(pattern: field)
    // Kotlin twin: `OuraLiveMotionHistory.payloadShape`.
    private static let payloadShape = try! NSRegularExpression(pattern: "^" + space + #"\{"# + space + field + space + "(?:," + space + field + space + #")*\}"# + space + "$")
    // Kotlin twin: `OuraLiveMotionSummary.numericKeys`.
    private static let numericKeys = Set(["count", "first_receipt_ms", "last_receipt_ms"] +
        ["x", "y", "z"].flatMap { axis in ["sum_\(axis)", "min_\(axis)", "max_\(axis)"] })
    // Kotlin twin: `OuraLiveMotionSummary.validate`.
    private func validate() throws {
        guard Set(values.keys) == Self.numericKeys else { throw InvalidPayload.invalid }
        let count = values["count"]!
        // Arithmetic bound only; no sensor sampling rate is assumed.
        guard count > 0, count <= Int64.max / 32768 else { throw InvalidPayload.invalid }
        let first = values["first_receipt_ms"]!, last = values["last_receipt_ms"]!
        guard first >= 0, last >= first, first / 1000 == last / 1000, count > 1 || first == last else { throw InvalidPayload.invalid }
        for axis in ["x", "y", "z"] {
            let low = values["min_\(axis)"]!, high = values["max_\(axis)"]!, sum = values["sum_\(axis)"]!
            guard low >= -32768, low <= high, high <= 32767 else { throw InvalidPayload.invalid }
            if count == 1 {
                guard low == high, sum == low else { throw InvalidPayload.invalid }
            } else {
                guard sum >= low * (count - 1) + high, sum <= high * (count - 1) + low else { throw InvalidPayload.invalid }
            }
        }
    }
    private enum InvalidPayload: Error { case invalid }
}
