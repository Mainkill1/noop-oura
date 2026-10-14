import SwiftUI
import StrandDesign
import StrandAnalytics
import WhoopStore

struct OuraView: View {
    @EnvironmentObject private var repo: Repository
    @State private var sources: [OuraDashboardSource] = []
    @State private var sourceId = ""
    @State private var landedSource = ""
    @State private var loadedKey = ""
    @State private var day = Calendar.current.startOfDay(for: Date())
    @State private var days = 1
    @State private var showRange = false
    @State private var data: OuraDashboardData?
    @State private var error = false
    @State private var loading = true
    @State private var revision = 0
    @State private var showDevices = false
    @State private var showImports = false
    @AppStorage(UnitPrefs.systemKey) private var unitSystemRaw = UnitSystem.metric.rawValue
    @AppStorage(UnitPrefs.temperatureKey) private var temperatureRaw = ""

    private var temperatureUnit: TemperatureUnit {
        UnitPrefs.resolveTemperature(system: UnitSystem(rawValue: unitSystemRaw) ?? .metric, override: temperatureRaw)
    }
    private var source: OuraDashboardSource? { sources.first { $0.id == sourceId } }
    private var bounds: ClosedRange<Date> {
        let cal = Calendar.current
        let end = cal.date(byAdding: .day, value: 1, to: cal.startOfDay(for: day))!
        let start = cal.date(byAdding: .day, value: -days, to: end)!
        return start...end
    }
    private var bucketSeconds: Int { days == 1 ? 300 : days == 7 ? 3600 : 21600 }
    private var loadKey: String { "\(sourceId)|\(day)|\(days)|\(repo.refreshSeq)|\(revision)" }

    var body: some View {
        ScreenScaffold(title: "Oura", subtitle: "Your ring, your history, clearer signals.", lazy: true) {
            controls
            if loading {
                ProgressView().accessibilityLabel("Loading Oura data")
            } else if error {
                NoopCard {
                    VStack(alignment: .leading, spacing: NoopMetrics.cardInnerSpacing) {
                        Text("Oura data could not be loaded completely. Try a shorter window or retry.")
                        Button("Retry") { revision += 1 }
                    }.font(StrandFont.body)
                }
            } else if loadedKey == loadKey, let data, let source {
                if source.isImport {
                    Text("Imported daily values. Oura scores are reference values, separate from NOOP scores.")
                        .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                } else {
                    Text("Median time buckets reduce visual noise. Show ranges to inspect the minimum and maximum; missing buckets remain gaps. Statistics use the original readings.")
                        .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                    Text("Bucket width: \(bucketSeconds / 60) minutes")
                        .font(StrandFont.captionNumber).foregroundStyle(StrandPalette.textSecondary)
                }
                if let latest = data.signals.values.compactMap(\.latestTs).max() {
                    Text("Latest reading in window: \(Date(timeIntervalSince1970: Double(latest)).formatted(date: .abbreviated, time: .shortened))")
                        .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                }
                ForEach(visibleKeys(data, imported: source.isImport), id: \.self) { key in
                    let presentation = presentation(key, imported: source.isImport)
                    OuraSignalCard(title: presentation.title, unit: presentation.unit, note: presentation.note,
                        summary: data.signals[key] ?? .init(), bounds: bounds, showRange: showRange,
                        transform: { value in
                            guard temperatureUnit == .fahrenheit else { return value }
                            if key == "temperature" { return UnitFormatter.celsiusToFahrenheit(value) }
                            if key == "skin_temp_dev_c" { return value * 9 / 5 }
                            return value
                        })
                        .id("\(loadKey)|\(key)")
                }
                sleepCard(data)
            }
        }
        .task(id: loadKey) { await load() }
        .sheet(isPresented: $showDevices) { sheet { DevicesView() } }
        .sheet(isPresented: $showImports) { sheet { DataSourcesView() } }
    }

    private var controls: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.cardInnerSpacing) {
            if !sources.isEmpty {
                Picker("Oura source", selection: $sourceId) {
                    ForEach(sources) { Text($0.name).tag($0.id) }
                }
            }
            HStack {
                Button { step(-days) } label: { Image(systemName: "chevron.left") }
                    .accessibilityLabel("Previous window")
                DatePicker("Ending", selection: $day, in: ...Date(), displayedComponents: .date)
                Button { step(days) } label: { Image(systemName: "chevron.right") }
                    .accessibilityLabel("Next window")
                    .disabled(Calendar.current.isDateInToday(day))
            }
            SegmentedPillControl([1, 7, 30], selection: $days) { String(localized: "\($0) days") }
            Toggle("Show ranges", isOn: $showRange).font(StrandFont.body)
            HStack {
                Button("Manage ring") { showDevices = true }
                Button("Import history") { showImports = true }
                Button("Refresh") { revision += 1 }.disabled(loading)
            }.font(StrandFont.caption)
        }
    }

    private func sheet<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        VStack {
            HStack { Spacer(); Button("Done") { showDevices = false; showImports = false; revision += 1 } }
                .padding(NoopMetrics.cardPadding)
            content()
        }
    }

    private func step(_ amount: Int) {
        let next = Calendar.current.date(byAdding: .day, value: amount, to: day)!
        day = min(next, Calendar.current.startOfDay(for: Date()))
    }

    @MainActor private func load() async {
        let requestedKey = loadKey
        let requestedId = sourceId
        let requestedBounds = bounds
        let requestedBucketSeconds = bucketSeconds
        // State can change before SwiftUI cancels the previous task. Identity is the commit guard.
        func isCurrent() -> Bool { !Task.isCancelled && loadKey == requestedKey && sourceId == requestedId }
        loading = true; error = false; data = nil
        do {
            let available = try await repo.ouraDashboardSources()
            guard isCurrent() else { return }
            sources = available
            guard let selected = available.first(where: { $0.id == requestedId }) else {
                sourceId = available.first(where: { $0.id == repo.deviceId })?.id ?? available.first?.id ?? ""
                return
            }
            if landedSource != requestedId {
                let latest = try await repo.ouraDashboardLatestDay(source: selected)
                guard isCurrent() else { return }
                landedSource = requestedId
                if let latest {
                    let target = Calendar.current.startOfDay(for: min(latest, Date()))
                    if target != day { day = target; return }
                }
            }
            let snapshot = try await repo.ouraDashboard(source: selected,
                from: Int(requestedBounds.lowerBound.timeIntervalSince1970),
                to: Int(requestedBounds.upperBound.timeIntervalSince1970), bucketSeconds: requestedBucketSeconds)
            guard isCurrent() else { return }
            data = snapshot; loadedKey = requestedKey; loading = false
        } catch is CancellationError {
            return
        } catch {
            if isCurrent() { self.error = true; loading = false }
        }
    }

    private func visibleKeys(_ data: OuraDashboardData, imported: Bool) -> [String] {
        guard imported else { return ["hr", "oxygen", "temperature", "hrv", "breathing", "movement", "battery"] }
        let available = OuraDashboardData.importedKeys.filter { (data.signals[$0]?.count ?? 0) > 0 }
        return available.isEmpty ? ["rhr"] : available
    }

    private func sleepCard(_ data: OuraDashboardData) -> some View {
        NoopCard {
            VStack(alignment: .leading, spacing: NoopMetrics.cardInnerSpacing) {
                Text("Sleep · Oura reported").font(StrandFont.headline)
                if data.sleeps.isEmpty { Text("No sleep sessions ending in this window.").font(StrandFont.caption) }
                ForEach(data.sleeps, id: \.startTs) { sleep in
                    let start = Date(timeIntervalSince1970: Double(sleep.effectiveStartTs))
                    let end = Date(timeIntervalSince1970: Double(sleep.endTs))
                    Text("\(start.formatted(date: .abbreviated, time: .shortened)) – \(end.formatted(date: .abbreviated, time: .shortened))")
                        .font(StrandFont.captionNumber)
                    if let totals = SleepStageTotals.minutes(fromStagesJSON:
                        SleepStageTotals.clampStagesToOnset(sleep.stagesJSON, onsetSec: sleep.effectiveStartTs)) {
                        Text("Asleep \(Int(totals.asleep.rounded())) min · Deep \(Int(totals.deep.rounded())) · Light \(Int(totals.light.rounded())) · REM \(Int(totals.rem.rounded()))")
                            .font(StrandFont.captionNumber)
                    }
                    if let decoded = SleepView.decodeSegments(sleep.stagesJSON, sessionStart: sleep.effectiveStartTs) {
                        Hypnogram(intervals: decoded.intervals, height: NoopMetrics.chartHeight,
                                  nightStart: start, showsTimeAxis: true)
                    } else {
                        Text("Stage timeline unavailable for this session.").font(StrandFont.caption)
                    }
                }
            }.foregroundStyle(StrandPalette.textSecondary)
        }
    }

    private func presentation(_ key: String, imported: Bool) -> (title: String, unit: String, note: String) {
        let temp = UnitFormatter.temperatureUnit(temperatureUnit)
        switch key {
        case "hr": return (String(localized: "Heart rate"), "bpm", "")
        case "oxygen": return (String(localized: "Oxygen signal · unvalidated"), "%", String(localized: "Raw ring oxygen readings can exceed 100 because calibration is unresolved. This is not a validated blood oxygen measurement."))
        case "temperature": return (String(localized: "Skin temperature"), temp, "")
        case "hrv": return (String(localized: "HRV · Oura reported"), "ms", imported ? "" : String(localized: "The ring’s stored RMSSD summaries, not HRV reconstructed from banked intervals. These display values do not enable NOOP Recovery scoring."))
        case "breathing": return (String(localized: "Breathing · ring summary"), String(localized: "breaths/min"), String(localized: "Experimental ring-reported rate; not a validated respiratory measurement or a scoring input."))
        case "movement": return (String(localized: "Movement"), "s", String(localized: "Seconds of movement per ring window. Movement is not a step count; gaps do not mean inactivity."))
        case "battery": return (String(localized: "Battery history"), "%", "")
        case "rhr": return (String(localized: "Resting heart rate"), "bpm", "")
        case "spo2": return (String(localized: "Blood oxygen · imported"), "%", "")
        case "resp_rate": return (String(localized: "Respiratory rate · imported"), String(localized: "breaths/min"), "")
        case "skin_temp_dev_c": return (String(localized: "Temperature deviation"), temp, "")
        case "steps": return (String(localized: "Steps · imported"), String(localized: "steps"), "")
        case "energy_kcal": return (String(localized: "Active energy"), "kcal", "")
        case "total_kcal": return (String(localized: "Total energy"), "kcal", "")
        case "sleep_total_min": return (String(localized: "Sleep duration"), "min", "")
        case "sleep_deep_min": return (String(localized: "Deep sleep"), "min", "")
        case "sleep_rem_min": return (String(localized: "REM sleep"), "min", "")
        case "ref_readiness_score": return (String(localized: "Oura readiness · reference"), "/100", "")
        case "ref_sleep_score": return (String(localized: "Oura sleep score · reference"), "/100", "")
        case "ref_activity_score": return (String(localized: "Oura activity score · reference"), "/100", "")
        default: return ("VO₂ max", "mL/kg/min", "")
        }
    }
}
