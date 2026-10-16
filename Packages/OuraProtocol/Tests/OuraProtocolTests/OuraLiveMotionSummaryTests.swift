import XCTest
@testable import OuraProtocol

final class OuraLiveMotionSummaryTests: XCTestCase {
    func testReciprocalMalformedAndBoundaryOracle() throws {
        var actual: [String] = []
        let zero = OuraLiveMotionSummary.sample(epochMs: 1000000, xyz: OuraRawXYZ(x: 0, y: 0, z: 0))
        let base = try zero.payloadJSON()
        let cases: [(String, String)] = [
            ("count", "true"), ("count", "\"1\""), ("count", "1.5"), ("count", "1.0"), ("count", "1e0"), ("count", "null"),
            ("count", "9223372036854775808"), ("count", "9223372036854775807"), ("count", "0"), ("count", "-1"),
            ("sum_x", "9223372036854775807"), ("sum_x", "-9223372036854775808"),
            ("min_x", "-32769"), ("max_x", "32768"), ("min_x", "1"), ("sum_x", "1"),
            ("first_receipt_ms", "1000001"), ("last_receipt_ms", "1001000"), ("first_receipt_ms", "-1")
        ]
        for (key, token) in cases {
            let payload = base.replacingOccurrences(of: "\"\(key)\":-?[0-9]+", with: "\"\(key)\":\(token)", options: .regularExpression)
            actual.append("\(key)=\(token):\((try? OuraLiveMotionSummary.parse(ts: 1000, payload: payload)) == nil ? "error" : "ok")")
        }
        var maxValues = zero.values; maxValues["count"] = 281474976710655
        let maximum = OuraLiveMotionSummary(values: maxValues)
        for (label, payload) in [
            ("valid", base),
            ("spaced", " \n" + base.replacingOccurrences(of: ",", with: ",\n ") + "\n\t"),
            ("duplicate", base.replacingOccurrences(of: "{", with: "{\"count\":1,")),
            ("unknown", base.replacingOccurrences(of: "{", with: "{\"extra\":0,")),
            ("missing", base.replacingOccurrences(of: "\"count\":1,", with: "")),
            ("maximum", try maximum.payloadJSON()),
            ("maximumNegative", try OuraLiveMotionSummary(values: maxValues.merging(["sum_x": -9223372036854743040, "min_x": -32768, "max_x": -32768]) { _, new in new }).payloadJSON())
        ] {
            actual.append("\(label):\((try? OuraLiveMotionSummary.parse(ts: 1000, payload: payload)) == nil ? "error" : "ok")")
        }
        var almostValues = maxValues; almostValues["count"]! -= 1
        actual.append("boundaryMerged:\(try OuraLiveMotionSummary(values: almostValues).merge(zero).values["count"]!)")
        actual.append("boundaryRejected:\((try? maximum.merge(zero)) == nil ? "error" : "ok")")
        var invalid = maxValues; invalid["count"] = Int64.max
        actual.append("constructedRejected:\((try? OuraLiveMotionSummary(values: invalid).merge(zero)) == nil ? "error" : "ok")")
        actual.append("missingRejected:\((try? OuraLiveMotionSummary(values: [:]).merge(zero)) == nil ? "error" : "ok")")
        actual.append("recovered:\(try zero.merge(zero).values["count"]!)")
        XCTAssertEqual(actual.joined(separator: "\n"), #"""
count=true:error
count="1":error
count=1.5:error
count=1.0:error
count=1e0:error
count=null:error
count=9223372036854775808:error
count=9223372036854775807:error
count=0:error
count=-1:error
sum_x=9223372036854775807:error
sum_x=-9223372036854775808:error
min_x=-32769:error
max_x=32768:error
min_x=1:error
sum_x=1:error
first_receipt_ms=1000001:error
last_receipt_ms=1001000:error
first_receipt_ms=-1:error
valid:ok
spaced:ok
duplicate:error
unknown:error
missing:error
maximum:ok
maximumNegative:ok
boundaryMerged:281474976710655
boundaryRejected:error
constructedRejected:error
missingRejected:error
recovered:2
"""#)
    }
    func testPersistedPayloadRejectsCoercionsImpossibleValuesAndInconsistentBounds() throws {
        let base = try OuraLiveMotionSummary.sample(epochMs: 1000000, xyz: OuraRawXYZ(x: 0, y: 0, z: 0)).payloadJSON()
        let cases = [
            ("count", "true"), ("count", "\"1\""), ("count", "1.5"), ("count", "1.0"), ("count", "1e0"),
            ("count", "9223372036854775808"), ("count", "9223372036854775807"), ("count", "0"),
            ("sum_x", "9223372036854775807"), ("sum_x", "-9223372036854775808"),
            ("min_x", "-32769"), ("max_x", "32768"), ("min_x", "1"), ("sum_x", "1"),
            ("first_receipt_ms", "1000001"), ("last_receipt_ms", "1001000"), ("first_receipt_ms", "-1")
        ]
        for (key, token) in cases {
            let bad = base.replacingOccurrences(of: "\"\(key)\":-?[0-9]+", with: "\"\(key)\":\(token)", options: .regularExpression)
            XCTAssertThrowsError(try OuraLiveMotionSummary.parse(ts: 1000, payload: bad), "\(key)=\(token)")
        }
        XCTAssertThrowsError(try OuraLiveMotionSummary.parse(ts: 1000, payload: base.replacingOccurrences(of: "{", with: "{\"extra\":0,")))
        XCTAssertNoThrow(try OuraLiveMotionSummary.parse(ts: 1000, payload: base))
    }
    func testMergeRejectsArithmeticBoundaryRecoverablyWithoutMutatingInputs() throws {
        let zero = OuraLiveMotionSummary.sample(epochMs: 1000000, xyz: OuraRawXYZ(x: 0, y: 0, z: 0))
        var values = zero.values; values["count"] = 281474976710655
        let largest = OuraLiveMotionSummary(values: values)
        let before = try largest.payloadJSON()
        XCTAssertThrowsError(try largest.merge(zero))
        XCTAssertEqual(try largest.payloadJSON(), before)
        values["count"] = Int64.max
        XCTAssertThrowsError(try OuraLiveMotionSummary(values: values).merge(zero))
    }
    func testStandaloneOraclePinsCompleteSignedInputAndSpread() throws {
        let expected = [
            #"{"count":257,"first_receipt_ms":1000000,"last_receipt_ms":1000256,"max_x":256,"max_y":4352,"max_z":7943,"min_x":0,"min_y":0,"min_z":7,"provenance":"host_receipt","sum_x":32896,"sum_y":559232,"sum_z":1021575,"units":"raw_signed16"}"#,
            #"{"count":65536,"first_receipt_ms":1000000,"last_receipt_ms":1000999,"max_x":32767,"max_y":32767,"max_z":32767,"min_x":-32768,"min_y":-32768,"min_z":-32768,"provenance":"host_receipt","sum_x":-32768,"sum_y":-32768,"sum_z":-32768,"units":"raw_signed16"}"#
        ]
        for (index, count) in [257, 65536].enumerated() {
            var summary: OuraLiveMotionSummary?
            for bits in 0..<count {
                func signed(_ bits: Int) -> Int { Int(Int16(truncatingIfNeeded: bits)) }
                let next = OuraLiveMotionSummary.sample(epochMs: Int64(1000000 + bits % 1000),
                    xyz: OuraRawXYZ(x: signed(bits), y: signed(bits * 17), z: signed(bits * 31 + 7)))
                summary = try summary?.merge(next) ?? next
            }
            XCTAssertEqual(try summary!.payloadJSON(), expected[index])
        }
    }
    func testPartialMergeRetainsExtremesAndReceiptOrder() throws {
        let a = OuraLiveMotionSummary.sample(epochMs: 1000123, xyz: OuraRawXYZ(x: -32768, y: 4, z: 32767))
        let b = OuraLiveMotionSummary.sample(epochMs: 1000789, xyz: OuraRawXYZ(x: 32767, y: -8, z: -32768))
        let merged = try b.merge(a)
        XCTAssertEqual(merged.values["count"], 2)
        XCTAssertEqual(merged.values["sum_x"], -1)
        XCTAssertEqual(merged.values["min_x"], -32768)
        XCTAssertEqual(merged.values["max_x"], 32767)
        XCTAssertEqual(merged.mean("x"), -0.5)
        XCTAssertEqual(try merged.payloadJSON(), try a.merge(b).payloadJSON())
    }
}
