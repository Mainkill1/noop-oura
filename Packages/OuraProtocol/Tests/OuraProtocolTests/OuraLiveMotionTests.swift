import XCTest
@testable import OuraProtocol

final class OuraLiveMotionTests: XCTestCase {
    func testSignedRawVectorsAndOpaqueMetadata() throws {
        let decoded = try XCTUnwrap(OuraLiveMotion.decode(OuraOuterFrame(op: 0x33,
            body: [0x31, 0xfe, 0, 0x80, 0xff, 0x7f, 0xff, 0xff, 0, 0, 1, 0, 0x34, 0x12])))
        XCTAssertEqual(decoded.metadataByte, 49)
        XCTAssertEqual(decoded.sequence, 254)
        XCTAssertEqual(decoded.samples, [OuraRawXYZ(x: -32768, y: 32767, z: -1), OuraRawXYZ(x: 0, y: 1, z: 4660)])
        XCTAssertEqual(OuraLiveMotion.decode(OuraOuterFrame(op: 0x33, body: [0, 255, 1, 0, 2, 0, 3, 0]))?.samples,
                       [OuraRawXYZ(x: 1, y: 2, z: 3)])
    }
    func testRejectsUnsupportedLengthsAndHistoricalTags() {
        for count in [0, 7, 9, 13, 15] {
            XCTAssertNil(OuraLiveMotion.decode(OuraOuterFrame(op: 0x33, body: Array(repeating: 0, count: count))))
        }
        XCTAssertNil(OuraLiveMotion.decode(OuraOuterFrame(op: 0x47, body: Array(repeating: 0, count: 14))))
    }
    func testBoundedCommandLiterals() {
        XCTAssertEqual(OuraCommands.liveMotionStart().bytes, [6,7,32,0,0,0,1,0,0])
        XCTAssertEqual(OuraCommands.liveMotionStop().bytes, [6,4,0,0,0,0])
    }
    func testAllSigned16ValuesStayPinnedToOracle() throws {
        var total: Int64 = 0
        var weighted: Int64 = 0
        var minimum = Int.max
        var maximum = Int.min
        for bits in 0...65535 {
            let frame = OuraOuterFrame(op: 0x33, body: [77, UInt8(bits & 255), UInt8(bits & 255), UInt8(bits >> 8), 0xaa, 0x55, 0x55, 0xaa])
            let sample = try XCTUnwrap(OuraLiveMotion.decode(frame)?.samples.first)
            total += Int64(sample.x)
            weighted += Int64(bits + 1) * Int64(sample.x)
            minimum = min(minimum, sample.x)
            maximum = max(maximum, sample.x)
        }
        XCTAssertEqual("65536:\(total):\(weighted):\(minimum):\(maximum)", "65536:-32768:-11729197793280:-32768:32767")
    }

}
