import XCTest
@testable import OuraProtocol

final class OuraRawInstrumentsTests: XCTestCase {
    func testCompleteEnvelopeAndSafeCapture() {
        XCTAssertTrue(OuraRawInstruments.capture([0x2f, 4, 4, 1, 2, 3]).isEmpty)
        XCTAssertTrue(OuraRawInstruments.capture([0x43, 5, 1, 0, 0, 0, 65]).isEmpty)
        XCTAssertTrue(OuraRawInstruments.capture([0x80, 6, 1, 0, 0, 0, 100, 8, 0x33, 8, 0]).isEmpty)
        XCTAssertEqual(OuraRawInstruments.capture([0x90, 6, 1, 0, 0, 0, 1, 255]).first?.channel, "event:90")
    }
    func testUnfilteredPairsAndResearchFallback() {
        let raw = OuraRawInstruments.capture([0x80, 10, 1, 0, 0, 0, 0, 0, 100, 8, 255, 31]).first!
        XCTAssertEqual(raw.fields.first { $0.key == "ibi" }?.values, [0, 800, 2047])
        XCTAssertEqual(raw.fields.first { $0.key == "quality" }?.values, [0, 1, 3])
        for tag: UInt8 in [0x81, 0x8b] {
            let capture = OuraRawInstruments.capture([tag, 8, 1, 0, 0, 0, 0x80, 255, 255, 255]).first!
            XCTAssertTrue(capture.fields.allSatisfy { $0.key.hasPrefix("byte[") })
        }
        let night = OuraRawInstruments.capture([0x60, 18, 1, 0, 0, 0] + Array(repeating: 0, count: 14)).first!
        XCTAssertEqual(night.fields.first { $0.key == "ibi" }?.values, Array(repeating: 0, count: 6))
    }
}
