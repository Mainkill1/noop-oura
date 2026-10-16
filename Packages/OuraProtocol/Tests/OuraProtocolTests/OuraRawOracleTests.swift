import Foundation
import XCTest
@testable import OuraProtocol

// The expected literal is the standalone Swift oracle stdout, also pasted verbatim into Kotlin.
final class OuraRawOracleTests: XCTestCase {
    func testExhaustiveQualityAndExtremeRecords() {
        XCTAssertEqual(rawOracleOutput(), """
quality-exhaustive sum=67174400 fnv=389e3b7cec6a9325
80[0] typed= rawFields=0
80[7] typed=ibi=0.0000,800.0000,2047.0000;quality=0.0000,1.0000,3.0000 rawFields=7
60[14] typed=ibi=0.0000,0.0000,0.0000,0.0000,0.0000,0.0000;amplitude=0.0000,0.0000,0.0000,0.0000,0.0000,0.0000 rawFields=14
60[14] typed=ibi=2047.0000,2047.0000,2047.0000,2047.0000,2047.0000,2047.0000;amplitude=8323072.0000,8323072.0000,8323072.0000,8323072.0000,8323072.0000,8323072.0000 rawFields=14
60[13] typed= rawFields=13
46[4] typed=temperature=-327.6800,327.6700 rawFields=4
46[1] typed= rawFields=1
47[6] typed=x=-1024.0000;y=1016.0000;z=-8.0000;motion=31.0000 rawFields=6
47[5] typed= rawFields=5
5d[4] typed=hr=0.0000,255.0000;rmssd=0.0000,255.0000 rawFields=4
5d[1] typed= rawFields=1
81[4] typed= rawFields=4
8b[5] typed= rawFields=5
90[2] typed= rawFields=2
stats 327.6700 -327.6800 327.6700 -0.0033 3
window 2 2 16 8 0
catalog event:41:record:0:0 event:42:record:0:0 event:43:record:0:0 event:45:record:0:0 event:53:record:0:0 event:85:record:0:0 event:60:record:1:1 event:71:record:0:1 event:6e:record:0:1 event:80:record:1:1 event:44:record:1:1 event:5d:record:1:1 event:6f:record:0:1 event:7b:record:0:1 event:77:record:0:1 event:46:record:1:1 event:69:record:1:1 event:75:record:1:1 event:47:record:1:1 event:6b:record:0:1 event:49:record:0:1 event:4c:record:0:1 event:4f:record:0:1 event:57:record:0:1 event:58:record:0:1 event:6a:record:0:1 event:4b:record:0:1 event:4e:record:0:1 event:5a:record:0:1 event:50:record:0:1 event:51:record:0:1 event:52:record:0:1 event:7e:record:0:1 event:7f:record:0:1 event:70:record:0:1 event:8b:record:0:1 event:81:record:0:1 hr:push:1:1 motion:push:1:1 battery:poll:1:1 status:02:poll:1:1 status:04:poll:1:1 status:0b:poll:1:1
""")
    }
}


func rawOracleOutput() -> String {
    var checksum: UInt64 = 14695981039346656037
    var total = 0
    for a in 0...255 {
        for b in 0...255 {
            let fields = OuraRawInstruments.recordFields(OuraRecord(type: 0x80, ringTimestamp: 1, payload: [UInt8(a), UInt8(b)]))
            for key in ["ibi", "quality"] {
                let value = Int(fields.first { $0.key == key }!.values[0])
                checksum = (checksum ^ UInt64(value)) &* 1099511628211
                total += value
            }
        }
    }
    let cases: [(UInt8, [UInt8])] = [
        (0x80, []), (0x80, [0, 0, 100, 8, 255, 31, 123]),
        (0x60, Array(repeating: 0, count: 14)), (0x60, Array(repeating: 255, count: 14)),
        (0x60, [1,2,3,4,5,6,7,8,9,10,11,12,13]),
        (0x46, [0, 128, 255, 127]), (0x46, [0]),
        (0x47, [0xff, 0x80, 0x7f, 0xff, 0, 63]), (0x47, [1, 2, 3, 4, 64]),
        (0x5d, [0, 0, 255, 255]), (0x5d, [99]),
        (0x81, [0x80, 255, 255, 255]), (0x8b, [1, 2, 3, 4, 5]), (0x90, [0, 255])]
    var lines = ["quality-exhaustive sum=\(total) fnv=\(String(checksum, radix: 16))"]
    for (tag, body) in cases {
        let fields = OuraRawInstruments.recordFields(OuraRecord(type: tag, ringTimestamp: 1, payload: body))
        let typed = fields.filter { !$0.key.hasPrefix("byte[") }.map { field in
            field.key + "=" + field.values.map { String(format: "%.4f", $0) }.joined(separator: ",")
        }.joined(separator: ";")
        lines.append(String(format: "%02x", tag) + "[\(body.count)] typed=\(typed) rawFields=\(fields.filter { $0.key.hasPrefix("byte[") }.count)")
    }
    let stats = OuraRawInstruments.statistics([-327.68, 0, 327.67])!
    lines.append(String(format: "stats %.4f %.4f %.4f %.4f %d", stats.latest, stats.minimum, stats.maximum, stats.mean, stats.count))
    let memory = OuraRawBuffer(maxRecords: 2, maxBytes: 20, maxValues: 40)
    for index in 0..<4 {
        memory.append(OuraRawInstruments.capture([0x80, 6, 1, 0, 0, 0, 100, 8])[0], receivedAtElapsedMs: Int64(index))
    }
    let snap = memory.snapshot()
    lines.append("window \(snap.records.count) \(snap.evictedRecords) \(snap.evictedBytes) \(snap.evictedValues) \(snap.truncatedRecords)")
    lines.append("catalog " + OuraRawInstruments.catalog.map { "\($0.key):\($0.acquisition):\($0.verified ? 1 : 0):\($0.supported ? 1 : 0)" }.joined(separator: " "))
    return lines.joined(separator: "\n")
}
