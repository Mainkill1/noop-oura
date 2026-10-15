import XCTest
import WhoopProtocol
import OuraProtocol
@testable import WhoopStore

final class OuraLiveMotionPersistenceTests: XCTestCase {
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
