import SwiftUI
import Charts
import StrandAnalytics
import StrandDesign

/// A median trace with optional per-bucket extrema. Empty buckets break the line.
struct OuraSignalCard: View {
    let title: String
    let unit: String
    let note: String
    let summary: SignalDecimation.Summary
    let bounds: ClosedRange<Date>
    let showRange: Bool
    var transform: (Double) -> Double = { $0 }
    @State private var selected: SignalDecimation.Bucket?

    private struct PlotBucket: Identifiable {
        let bucket: SignalDecimation.Bucket
        let segment: Int
        var id: Int { bucket.start }
        var date: Date { Date(timeIntervalSince1970: Double(bucket.start + (bucket.end - bucket.start) / 2)) }
    }

    private var plotted: [PlotBucket] {
        var segment = 0
        return summary.buckets.enumerated().map { index, bucket in
            if index > 0 && summary.buckets[index - 1].end != bucket.start { segment += 1 }
            return PlotBucket(bucket: bucket, segment: segment)
        }
    }

    private func formatted(_ value: Double) -> String {
        transform(value).formatted(.number.precision(.fractionLength(1))) + " " + unit
    }

    var body: some View {
        NoopCard {
            VStack(alignment: .leading, spacing: NoopMetrics.cardInnerSpacing) {
                Text(title).font(StrandFont.headline).foregroundStyle(StrandPalette.textPrimary)
                if !note.isEmpty {
                    Text(note).font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                }
                if summary.count == 0 {
                    Text("No readings in this window. Sync the ring, import history, or choose another date.")
                        .font(StrandFont.body).foregroundStyle(StrandPalette.textSecondary)
                } else {
                    chart
                    if let selected {
                        Text(Date(timeIntervalSince1970: Double(selected.start)), style: .date)
                            .font(StrandFont.caption)
                        Text("\(Date(timeIntervalSince1970: Double(selected.start)).formatted(date: .omitted, time: .shortened))–\(Date(timeIntervalSince1970: Double(selected.end)).formatted(date: .omitted, time: .shortened)) · Median \(formatted(selected.median)) · Range \(formatted(selected.minimum))–\(formatted(selected.maximum)) · \(selected.count) readings")
                            .font(StrandFont.captionNumber).foregroundStyle(StrandPalette.textPrimary)
                    } else {
                        Text("Tap or hover to inspect a time bucket.")
                            .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                    }
                    if let mean = summary.mean, let low = summary.minimum, let high = summary.maximum {
                        Text("Mean \(formatted(mean)) · Min \(formatted(low)) · Max \(formatted(high))")
                            .font(StrandFont.captionNumber).foregroundStyle(StrandPalette.textSecondary)
                    }
                    Text("\(summary.count) readings → \(summary.buckets.count) plotted buckets")
                        .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                }
            }
        }
    }

    private var chart: some View {
        Chart(plotted) { point in
            if showRange {
                RectangleMark(
                    xStart: .value("Start", Date(timeIntervalSince1970: Double(point.bucket.start))),
                    xEnd: .value("End", Date(timeIntervalSince1970: Double(point.bucket.end))),
                    yStart: .value("Minimum", transform(point.bucket.minimum)),
                    yEnd: .value("Maximum", transform(point.bucket.maximum)))
                    .foregroundStyle(StrandPalette.accent.opacity(0.15))
            }
            LineMark(x: .value("Time", point.date), y: .value("Median", transform(point.bucket.median)),
                     series: .value("Segment", point.segment))
                .interpolationMethod(.linear)
                .foregroundStyle(StrandPalette.accent)
            PointMark(x: .value("Time", point.date), y: .value("Median", transform(point.bucket.median)))
                .symbolSize(8).foregroundStyle(StrandPalette.accent)
        }
        .chartXScale(domain: bounds)
        .chartYScale(domain: yDomain)
        .chartOverlay { proxy in
            GeometryReader { geometry in
                Rectangle().fill(.clear).contentShape(Rectangle())
                    .gesture(DragGesture(minimumDistance: 0).onChanged { value in
                        inspect(value.location, proxy: proxy, geometry: geometry)
                    })
                    .onContinuousHover { phase in
                        if case .active(let location) = phase { inspect(location, proxy: proxy, geometry: geometry) }
                    }
            }
        }
        .frame(height: NoopMetrics.chartHeight)
        .accessibilityLabel(Text(title))
        .accessibilityValue(Text("\(summary.count) readings in \(summary.buckets.count) time buckets"))
    }

    private var yDomain: ClosedRange<Double> {
        let values = summary.buckets.flatMap { showRange ? [$0.minimum, $0.maximum] : [$0.median] }.map(transform)
        let low = values.min() ?? 0, high = values.max() ?? 1
        let pad = max((high - low) * 0.08, 0.5)
        return (low - pad)...(high + pad)
    }

    private func inspect(_ point: CGPoint, proxy: ChartProxy, geometry: GeometryProxy) {
        let frame = geometry[proxy.plotAreaFrame]
        guard frame.contains(point), let date: Date = proxy.value(atX: point.x - frame.minX) else {
            selected = nil; return
        }
        let ts = Int(date.timeIntervalSince1970)
        selected = summary.buckets.first { ts >= $0.start && ts < $0.end }
    }
}
