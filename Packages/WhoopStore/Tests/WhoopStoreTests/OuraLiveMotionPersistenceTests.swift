import XCTest
import WhoopProtocol
import OuraProtocol
@testable import WhoopStore

final class OuraLiveMotionPersistenceTests: XCTestCase {
    func testRejectedMergePreservesPriorRowAndRollsBackOtherWrites() async throws {
        let store = try await WhoopStore.inMemory()
        let zero = OuraLiveMotionSummary.sample(epochMs: 1000000, xyz: OuraRawXYZ(x: 0, y: 0, z: 0))
        var values = zero.values; values["count"] = 281474976710655
        let boundary = try OuraLiveMotionSummary(values: values).payloadJSON()
        try await store.recordEvent(deviceId: "oura-a", ts: 1000, kind: OuraLiveMotionSummary.eventKind, payloadJSON: boundary)
        var payload = zero.values.mapValues { ParsedValue.int(Int($0)) }
        payload["provenance"] = .string("host_receipt"); payload["units"] = .string("raw_signed16")
        let incoming = Streams(hr: [HRSample(ts: 1001, bpm: 60)],
                               events: [WhoopEvent(ts: 1000, kind: OuraLiveMotionSummary.eventKind, payload: payload)])
        do {
            _ = try await store.insert(incoming, deviceId: "oura-a")
            XCTFail("A count beyond the arithmetic bound must fail recoverably")
        } catch { }
        let rows = try await store.events(deviceId: "oura-a", from: 999, to: 1002, limit: 100)
        XCTAssertEqual(try WhoopStore.encodePayload(rows[0].payload), boundary)
        let hr = try await store.hrSamples(deviceId: "oura-a", from: 999, to: 1002, limit: 100)
        XCTAssertTrue(hr.isEmpty)

        // Seed malformed persisted bytes using the raw app-event boundary; merging must preserve them.
        let corrupt = boundary.replacingOccurrences(of: "281474976710655", with: "9223372036854775807")
        try await store.recordEvent(deviceId: "oura-b", ts: 1000, kind: OuraLiveMotionSummary.eventKind, payloadJSON: corrupt)
        do {
            _ = try await store.insert(incoming, deviceId: "oura-b")
            XCTFail("Malformed previous bytes must not be rewritten")
        } catch { }
        let corruptRows = try await store.events(deviceId: "oura-b", from: 999, to: 1002, limit: 100)
        XCTAssertEqual(try WhoopStore.encodePayload(corruptRows[0].payload), corrupt)
        let otherHR = try await store.hrSamples(deviceId: "oura-b", from: 999, to: 1002, limit: 100)
        XCTAssertTrue(otherHR.isEmpty)
    }
    func testConcurrentPartialSecondsMergeWithoutReplacingRingMotionOrAnotherSource() async throws {
        let store = try await WhoopStore.inMemory()
        func streams(_ ms: Int64, _ x: Int) -> Streams {
            let summary = OuraLiveMotionSummary.sample(epochMs: ms, xyz: OuraRawXYZ(x: x, y: -x, z: 3))
            var payload = summary.values.mapValues { ParsedValue.int(Int($0)) }
            payload["provenance"] = .string("host_receipt"); payload["units"] = .string("raw_signed16")
            return Streams(events: [WhoopEvent(ts: Int(summary.ts), kind: OuraLiveMotionSummary.eventKind, payload: payload)])
        }
        _ = try await store.insert(Streams(events: [WhoopEvent(ts: 1000, kind: "OURA_MOTION", payload: ["motion_seconds": .int(3)])]), deviceId: "oura-a")
        try await withThrowingTaskGroup(of: Void.self) { group in
            for index in 0..<20 {
                let batch = streams(1000000 + Int64(index), index)
                group.addTask { _ = try await store.insert(batch, deviceId: "oura-a") }
            }
            try await group.waitForAll()
        }
        _ = try await store.insert(streams(1000001, 99), deviceId: "oura-b")
        let events = try await store.events(deviceId: "oura-a", from: 999, to: 1002, limit: 100)
        let live = try XCTUnwrap(events.first { $0.kind == OuraLiveMotionSummary.eventKind })
        let summary = try OuraLiveMotionSummary.parse(ts: Int64(live.ts), payload: WhoopStore.encodePayload(live.payload))
        XCTAssertEqual(summary.values["count"], 20)
        XCTAssertEqual(summary.mean("x"), 9.5)
        XCTAssertEqual(summary.values["min_x"], 0); XCTAssertEqual(summary.values["max_x"], 19)
        XCTAssertEqual(summary.values["first_receipt_ms"], 1000000)
        XCTAssertEqual(summary.values["last_receipt_ms"], 1000019)
        XCTAssertEqual(events.filter { $0.kind == "OURA_MOTION" }.count, 1)
        let other = try await store.events(deviceId: "oura-b", from: 999, to: 1002, limit: 100)
        XCTAssertEqual(other.count, 1)
        XCTAssertEqual(other[0].payload["count"], .int(1))
    }
}
