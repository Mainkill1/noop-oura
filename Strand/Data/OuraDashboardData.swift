import Foundation
import StrandAnalytics
import WhoopStore

struct OuraDashboardSource: Identifiable, Equatable {
    let id: String
    let name: String
    var isImport: Bool { id == "oura-import" || id == "oura-api" }
}

struct OuraDashboardData {
    var signals: [String: SignalDecimation.Summary] = [:]
    var sleeps: [CachedSleepSession] = []
    static let importedKeys = ["rhr", "hrv", "spo2", "resp_rate", "skin_temp_dev_c", "steps", "energy_kcal",
                               "total_kcal", "sleep_total_min", "sleep_deep_min", "sleep_rem_min",
                               "ref_readiness_score", "ref_sleep_score", "ref_activity_score", "vo2max"]

    enum ReadError: Error { case unavailable, truncated }

    /// No union with the active WHOOP or other rings. Failed/capped reads fail the snapshot visibly.
    static func load(store: WhoopStore, source: OuraDashboardSource,
                     from: Int, to: Int, bucketSeconds: Int) async throws -> OuraDashboardData {
        var result = OuraDashboardData()
        let limit = 200_001
        func checked<T>(_ rows: [T]) throws -> [T] {
            guard rows.count < limit else { throw ReadError.truncated }
            return rows
        }
        if source.isImport {
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.calendar = Calendar(identifier: .gregorian)
            formatter.dateFormat = "yyyy-MM-dd"
            let first = formatter.string(from: Date(timeIntervalSince1970: Double(from)))
            let last = formatter.string(from: Date(timeIntervalSince1970: Double(to - 1)))
            let daily = try await store.dailyMetrics(deviceId: source.id, from: first, to: last)
            for key in importedKeys {
                try Task.checkCancellation()
                let series = try await store.metricSeries(deviceId: source.id, key: key, from: first, to: last)
                var byDay = Dictionary(uniqueKeysWithValues: series.map { ($0.day, $0.value) })
                for row in daily where byDay[row.day] == nil {
                    if let value = dailyValue(row, key: key) { byDay[row.day] = value }
                }
                // Daily imports retain their stated local date; no invented intraday curve.
                var summary = SignalDecimation.Summary()
                for day in byDay.keys.sorted() {
                    guard let date = formatter.date(from: day),
                          let end = Calendar.current.date(byAdding: .day, value: 1, to: date) else { continue }
                    let ts = Int(date.timeIntervalSince1970)
                    summary.append(SignalDecimation.reduce([.init(ts: ts, value: byDay[day]!)],
                        from: ts, to: Int(end.timeIntervalSince1970), bucketSeconds: Int(end.timeIntervalSince(date))))
                }
                result.signals[key] = summary
            }
        } else {
            var start = from
            while start < to {
                try Task.checkCancellation()
                // 24h batches divide all supported bucket widths; the last DST batch may be shorter.
                let end = min(to, start + 86_400)
                var raw: [String: [SignalDecimation.Sample]] = [:]
                raw["hr"] = try checked(await store.hrSamples(deviceId: source.id, from: start, to: end - 1, limit: limit))
                    .filter { $0.bpm > 0 && $0.bpm <= 300 }.map { .init(ts: $0.ts, value: Double($0.bpm)) }
                raw["oxygen"] = try checked(await store.spo2Samples(deviceId: source.id, from: start, to: end - 1, limit: limit))
                    .compactMap { row in
                        guard row.ir <= 0, AnalyticsEngine.spo2SingleChannelPlausible.contains(row.red) else { return nil }
                        return .init(ts: row.ts, value: Double(row.red))
                    }
                raw["temperature"] = try checked(await store.skinTempSamples(deviceId: source.id, from: start, to: end - 1, limit: limit))
                    .map { .init(ts: $0.ts, value: Double($0.raw) / 100) }
                raw["breathing"] = try checked(await store.respSamples(deviceId: source.id, from: start, to: end - 1, limit: limit))
                    .map { .init(ts: $0.ts, value: OuraRespScale.displayValue(raw: $0.raw, deviceId: source.id)) }
                raw["battery"] = try checked(await store.batterySamples(deviceId: source.id, from: start, to: end - 1, limit: limit))
                    .compactMap { row in row.soc.map { .init(ts: row.ts, value: $0) } }
                let events = try checked(await store.events(deviceId: source.id, from: start, to: end - 1, limit: limit))
                raw["hrv"] = events.compactMap { e in
                    guard e.kind == OuraStreamMapping.hrvEventKind,
                          let value = e.payload["rmssd_ms"]?.intValue, value > 0 else { return nil }
                    return .init(ts: e.ts, value: Double(value))
                }
                raw["movement"] = events.compactMap { e in
                    guard e.kind == OuraStreamMapping.motionEventKind,
                          let value = e.payload["motion_seconds"]?.intValue, value >= 0 else { return nil }
                    return .init(ts: e.ts, value: Double(value))
                }
                for (key, samples) in raw {
                    var summary = result.signals[key] ?? .init()
                    summary.append(SignalDecimation.reduce(samples, from: start, to: end, bucketSeconds: bucketSeconds))
                    result.signals[key] = summary
                }
                start = end
            }
        }
        // Include nights beginning before the window, then select by wake date.
        let sleeps = try await store.sleepSessions(deviceId: source.id, from: from - 172_800, to: to - 1, limit: 2001)
        guard sleeps.count < 2001 else { throw ReadError.truncated }
        result.sleeps = sleeps.filter { $0.endTs >= from && $0.endTs < to }.sorted { $0.endTs > $1.endTs }
        return result
    }

    private static func dailyValue(_ row: DailyMetric, key: String) -> Double? {
        switch key {
        case "rhr": return row.restingHr.map(Double.init)
        case "hrv": return row.avgHrv
        case "spo2": return row.spo2Pct
        case "resp_rate": return row.respRateBpm
        case "skin_temp_dev_c": return row.skinTempDevC
        case "steps": return row.steps.map(Double.init)
        case "energy_kcal": return row.activeKcalEst
        case "sleep_total_min": return row.totalSleepMin
        case "sleep_deep_min": return row.deepMin
        case "sleep_rem_min": return row.remMin
        default: return nil
        }
    }
}
