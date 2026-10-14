import Foundation

/// Display-only reduction. Statistics retain the original samples; medians do not feed scoring.
/// Kotlin twin: analytics/SignalDecimation.kt.
public enum SignalDecimation {
    public struct Sample: Sendable {
        public let ts: Int
        public let value: Double
        public init(ts: Int, value: Double) { self.ts = ts; self.value = value }
    }

    public struct Bucket: Identifiable, Sendable {
        public let start: Int
        public let end: Int
        public let median: Double
        public let minimum: Double
        public let maximum: Double
        public let count: Int
        public var id: Int { start }
    }

    public struct Summary: Sendable {
        public var buckets: [Bucket] = []
        public var count: Int = 0
        public var sum: Double = 0
        public var minimum: Double?
        public var maximum: Double?
        public var latestTs: Int?
        public var mean: Double? { count > 0 ? sum / Double(count) : nil }
        public init() {}

        /// Batches must cover disjoint, bucket-aligned windows in ascending order.
        public mutating func append(_ other: Summary) {
            buckets += other.buckets
            count += other.count
            sum += other.sum
            if let v = other.minimum { minimum = min(minimum ?? v, v) }
            if let v = other.maximum { maximum = max(maximum ?? v, v) }
            if let ts = other.latestTs { latestTs = max(latestTs ?? ts, ts) }
        }
    }

    public static func reduce(_ samples: [Sample], from: Int, to: Int,
                              bucketSeconds: Int) -> Summary {
        guard to > from else { return Summary() }
        let width = max(1, bucketSeconds)
        var values: [Int: [Double]] = [:]
        var result = Summary()
        for p in samples where p.ts >= from && p.ts < to && p.value.isFinite {
            let key = from + ((p.ts - from) / width) * width
            values[key, default: []].append(p.value)
            result.count += 1
            result.sum += p.value
            result.minimum = min(result.minimum ?? p.value, p.value)
            result.maximum = max(result.maximum ?? p.value, p.value)
            result.latestTs = max(result.latestTs ?? p.ts, p.ts)
        }
        result.buckets = values.keys.sorted().map { key in
            let sorted = values[key]!.sorted()
            let middle = sorted.count / 2
            let median = sorted.count.isMultiple(of: 2)
                ? sorted[middle - 1] / 2 + sorted[middle] / 2 : sorted[middle]
            return Bucket(start: key, end: min(to, key + width), median: median,
                          minimum: sorted.first!, maximum: sorted.last!, count: sorted.count)
        }
        return result
    }
}
