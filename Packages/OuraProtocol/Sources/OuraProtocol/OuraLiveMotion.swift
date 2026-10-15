import Foundation

/// Raw live accelerometer counts, without g calibration or ring-time interpretation.
public struct OuraRawXYZ: Equatable, Sendable {
    public let x: Int
    public let y: Int
    public let z: Int
    public init(x: Int, y: Int, z: Int) { self.x = x; self.y = y; self.z = z }
}

public struct OuraLiveMotionPacket: Equatable, Sendable {
    public let metadataByte: UInt8
    public let sequence: UInt8
    public let samples: [OuraRawXYZ]
}

/// Strict candidate response layout, independently implemented from OURA_LIVE_PROTOCOL.md facts.
public enum OuraLiveMotion {
    public static func decode(_ frame: OuraOuterFrame) -> OuraLiveMotionPacket? {
        let bytes = frame.body
        guard frame.op == 0x33, bytes.count == 8 || bytes.count == 14 else { return nil }
        func signed16(_ offset: Int) -> Int {
            Int(Int16(bitPattern: UInt16(bytes[offset]) | (UInt16(bytes[offset + 1]) << 8)))
        }
        let samples = stride(from: 2, to: bytes.count, by: 6).map { offset in
            OuraRawXYZ(x: signed16(offset), y: signed16(offset + 2), z: signed16(offset + 4))
        }
        return OuraLiveMotionPacket(metadataByte: bytes[0], sequence: bytes[1], samples: samples)
    }
}
