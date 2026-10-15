import XCTest
@testable import OuraProtocol

final class OuraLiveMotionSummaryTests: XCTestCase {
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
                summary = summary?.merge(next) ?? next
            }
            XCTAssertEqual(try summary!.payloadJSON(), expected[index])
        }
    }
    func testPartialMergeRetainsExtremesAndReceiptOrder() throws {
        let a = OuraLiveMotionSummary.sample(epochMs: 1000123, xyz: OuraRawXYZ(x: -32768, y: 4, z: 32767))
        let b = OuraLiveMotionSummary.sample(epochMs: 1000789, xyz: OuraRawXYZ(x: 32767, y: -8, z: -32768))
        let merged = b.merge(a)
        XCTAssertEqual(merged.values["count"], 2)
        XCTAssertEqual(merged.values["sum_x"], -1)
        XCTAssertEqual(merged.values["min_x"], -32768)
        XCTAssertEqual(merged.values["max_x"], 32767)
        XCTAssertEqual(merged.mean("x"), -0.5)
        XCTAssertEqual(try merged.payloadJSON(), try a.merge(b).payloadJSON())
    }
}
