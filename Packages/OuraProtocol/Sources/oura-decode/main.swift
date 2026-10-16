import Foundation
import OuraProtocol

// oura-decode: replay captured raw Oura records into decoded events with the SAME OuraProtocol
// decoder the macOS/iOS app uses, from the command line on any platform (Linux included). This is the
// headless protocol-RE workflow's Swift half: a capture tool records raw TLV records off a ring into a
// capture JSON file, and this decodes them, guaranteeing the RE path and the app agree byte for byte
// (no second decoder to drift). Mirrors whoop-decode.
//
// Usage:
//   oura-decode [options] [FILE]
//   cat capture.json | oura-decode --gen gen3
//   oura-decode --hex 600e0100000000010401000000000000
//
// Input (any one of):
//   FILE            a capture/fixture JSON file: an array of {"hex": ...} objects. The richer capture
//                   format ({"hex","kind","ts_ms"}) is a superset and is read too.
//   (stdin)         the same JSON, piped in, when no FILE is given.
//   --hex HEX ...   one or more raw record hex strings instead of a file.
//
// Options:
//   --gen G         gen3 | gen4 | gen5   (default: gen3, the verified-corpus generation)
//   --allow-tier-b  also decode Tier-B (UNVERIFIED) tags. OFF by default so Tier-B never ships values.
//   --json          emit decoded events as JSON instead of a text dump
//   -h, --help      show this help

// MARK: - Input model

/// One input record. `hex` is required; `kind`/`tsMs` are provenance the decoder ignores.
struct CaptureRecord: Decodable {
    let hex: String
    let kind: String?
    let tsMs: Int64?
    enum CodingKeys: String, CodingKey { case hex, kind; case tsMs = "ts_ms" }
}

// MARK: - Arg parsing (dependency-free)

func die(_ msg: String) -> Never {
    FileHandle.standardError.write(Data((msg + "\n").utf8))
    exit(2)
}

let helpText = """
oura-decode: decode captured Oura records with the OuraProtocol decoder.

USAGE:
  oura-decode [--gen gen3|gen4|gen5] [--allow-tier-b] [--json] [FILE]
  cat capture.json | oura-decode --gen gen3
  oura-decode --hex 600e0100000000010401000000000000
  oura-decode --raw-events [FILE | --hex HEX ...]
  oura-decode --live-motion [--json] [FILE | --hex HEX ...]
  oura-decode --live-motion-commands
  oura-decode --live-hr-commands
  oura-decode --live-motion-summary [FILE]

Summary mode requires ts_ms on each captured notification and exports host-time
seconds with count, sums, means, extrema and receipt bounds as JSON.

Live motion replays captured notifications offline; commands prints finite Start/OFF bytes
and exits without reading stdin. No command is sent. Metadata remains opaque raw data.
Live HR commands prints foreground entry and automatic-mode restoration bytes offline.

Reads a capture/fixture JSON array of {"hex": ...} from FILE or stdin, or raw
records from --hex. Generation defaults to gen3. Tier-B (UNVERIFIED) tags are
dropped unless --allow-tier-b is passed, so unverified layouts never feed values.
"""

var gen: OuraRingGen = .gen3
var allowTierB = false
var jsonOut = false
var rawEvents = false
var liveMotion = false
var liveMotionCommands = false
var liveMotionSummary = false
var genExplicit = false
var hexArgs: [String] = []
var filePath: String?

var args = Array(CommandLine.arguments.dropFirst())
if args.contains("--live-hr-commands") {
    guard args == ["--live-hr-commands"] else {
        die("--live-hr-commands cannot be combined with capture or decoding options")
    }
    for command in [OuraCommands.liveHREnable(), OuraCommands.liveHRSubscribe(),
                    OuraCommands.liveHRRestoreAutomatic(), OuraCommands.liveHRUnsubscribe()] {
        print(command.label + " " + command.bytes.map { String(format: "%02x", $0) }.joined())
    }
    exit(0)
}
var i = 0
while i < args.count {
    let a = args[i]
    switch a {
    case "-h", "--help":
        print(helpText); exit(0)
    case "--json": jsonOut = true
    case "--raw-events": rawEvents = true
    case "--live-motion": liveMotion = true
    case "--live-motion-summary": liveMotionSummary = true; liveMotion = true
    case "--live-motion-commands": liveMotionCommands = true
    case "--allow-tier-b": allowTierB = true
    case "--gen":
        genExplicit = true
        i += 1
        guard i < args.count else { die("--gen needs a value") }
        switch args[i] {
        case "gen3": gen = .gen3
        case "gen4": gen = .gen4
        case "gen5": gen = .gen5
        default: die("--gen must be gen3|gen4|gen5")
        }
    case "--hex":
        i += 1
        while i < args.count && !args[i].hasPrefix("--") {
            hexArgs.append(args[i]); i += 1
        }
        continue
    default:
        if a.hasPrefix("-") { die("unknown option: \(a)") }
        guard filePath == nil else { die("only one capture FILE may be provided") }
        filePath = a
    }
    i += 1
}

// Offline inspection only: builders produce bytes; this executable has no transport.
if liveMotionCommands {
    guard !liveMotion, !jsonOut, !allowTierB, !genExplicit, hexArgs.isEmpty, filePath == nil else {
        die("--live-motion-commands cannot be combined with capture or decoding options")
    }
    for command in [OuraCommands.liveMotionStart(), OuraCommands.liveMotionStop()] {
        print(command.label + " " + command.bytes.map { String(format: "%02x", $0) }.joined())
    }
    exit(0)
}

guard hexArgs.isEmpty || filePath == nil else { die("FILE and --hex cannot be combined") }

// MARK: - Gather input records

func loadJSON(_ data: Data) -> [CaptureRecord] {
    do {
        return try JSONDecoder().decode([CaptureRecord].self, from: data)
    } catch {
        die("could not parse capture JSON: \(error)")
    }
}

var records: [CaptureRecord] = []
if !hexArgs.isEmpty {
    records = hexArgs.map { CaptureRecord(hex: $0, kind: nil, tsMs: nil) }
} else if let path = filePath {
    guard let data = FileManager.default.contents(atPath: path) else { die("cannot read file: \(path)") }
    records = loadJSON(data)
} else {
    let data = FileHandle.standardInput.readDataToEndOfFile()
    guard !data.isEmpty else { die(helpText) }
    records = loadJSON(data)
}

// MARK: - Decode helpers

func bytes(fromHex hex: String) -> [UInt8]? {
    let s = hex.trimmingCharacters(in: .whitespacesAndNewlines)
    guard s.count % 2 == 0 else { return nil }
    var out = [UInt8](); out.reserveCapacity(s.count / 2)
    var idx = s.startIndex
    while idx < s.endIndex {
        let next = s.index(idx, offsetBy: 2)
        guard let b = UInt8(s[idx..<next], radix: 16) else { return nil }
        out.append(b); idx = next
    }
    return out
}

/// Left- or right-justify `s` to `width` with spaces (manual, to avoid String(format:) on Linux).
func pad(_ s: String, _ width: Int, right: Bool = false) -> String {
    if s.count >= width { return s }
    let fill = String(repeating: " ", count: width - s.count)
    return right ? fill + s : s + fill
}

/// A one-line human description of a decoded event.
func describe(_ e: OuraEvent) -> String {
    switch e {
    case .hr(let v): return "HR bpm=\(v.bpm) ibi=\(v.ibiMs)ms rt=\(v.ringTimestamp)"
    case .ibi(let v): return "IBI \(v.ibiMs)ms amp=\(v.amplitude.map(String.init) ?? "-") rt=\(v.ringTimestamp)"
    case .hrv(let v): return "HRV idx=\(v.index) hr=\(v.hrBpm)bpm rmssd=\(v.rmssdMs)ms rt=\(v.ringTimestamp)"
    case .spo2(let v): return "SPO2 \(v.value) (\(v.unit)) rt=\(v.ringTimestamp)"
    case .temp(let v): return "TEMP \(v.celsius)C rt=\(v.ringTimestamp)"
    case .battery(let v): return "BATTERY \(v.percent)% mv=\(v.voltageMv.map(String.init) ?? "-")"
    case .sleepPhase(let v): return "SLEEP_PHASE [\(v.index)]=\(v.stage) rt=\(v.ringTimestamp)"
    case .motion(let v): return "MOTION [\(v.index)]=\(v.state) rt=\(v.ringTimestamp)"
    case .motionEvent(let v): return "MOTION_EVENT orient=\(v.orientation) motionS=\(v.motionSeconds) xyz=(\(v.avgX),\(v.avgY),\(v.avgZ)) lo=\(v.lowIntensity.map(String.init) ?? "-") hi=\(v.highIntensity.map(String.init) ?? "-") rt=\(v.ringTimestamp)"
    case .state(let v): return "STATE code=\(v.stateCode) text=\(v.text ?? "-") rt=\(v.ringTimestamp)"
    case .timeSync(let v): return "TIME_SYNC epochMs=\(v.epochMs) tz=\(v.tzOffsetSeconds)s"
    case .rtcBeacon(let v): return "RTC_BEACON unix=\(v.unixSeconds)"
    case .debugText(_, let t): return "DEBUG \(t)"
    case .tierB(let v): return "TIER_B[UNVERIFIED] tag=0x\(String(v.tag, radix: 16)) kind=\(v.kind) bytes=\(v.rawPayload.count)"
    case .activityInfo(let v): return "ACTIVITY[TIER-B,UNVERIFIED] state=\(v.state) met=\(v.met) rt=\(v.ringTimestamp)"
    case .realStepsFields(let v): return "REAL_STEPS[TIER-B,UNVERIFIED] tag=0x\(String(v.tag, radix: 16)) fields=\(v.fields) rt=\(v.ringTimestamp)"
    case .sleepPeriodInfo(let v): return "SLEEP_PERIOD[TIER-B,UNVERIFIED] hr=\(v.averageHrBpm) trend=\(v.hrTrend) breath=\(v.breathsPerMin) breathV=\(v.breathVariability) mzci=\(v.mzci) dzci=\(v.dzci) motion=\(v.motionCount) state=\(v.sleepState) cv=\(v.cv) rt=\(v.ringTimestamp)"
    }
}

// MARK: - Run

let driver = OuraDriver(ringGen: gen, authKey: nil, allowTierB: allowTierB)
let reassembler = OuraReassembler()
let rawWindow = OuraRawBuffer()

struct JSONLine: Encodable { let index: Int; let kind: String; let detail: String }
var jsonLines: [JSONLine] = []
var total = 0
var decodedCount = 0
var tagCounts: [String: Int] = [:]
var motionSummaries: [Int64: OuraLiveMotionSummary] = [:]

for (n, rec) in records.enumerated() {
    guard let raw = bytes(fromHex: rec.hex) else {
        FileHandle.standardError.write(Data("skipping bad hex at index \(n)\n".utf8))
        continue
    }
    total += 1
    if rawEvents {
        let captures = OuraRawInstruments.capture(raw)
        for capture in captures {
            rawWindow.append(capture, receivedAtElapsedMs: rec.tsMs ?? 0)
            let bytes = capture.wire.map { String(format: "%02x", $0) }.joined(separator: " ")
            let fields = capture.fields.map { field in "\(field.key) [\(field.unit)]=" + field.values.map(String.init(describing:)).joined(separator: ",") }.joined(separator: "; ")
            let summaries = capture.fields.compactMap { field -> String? in
                guard let stats = OuraRawInstruments.statistics(field.values) else { return nil }
                return "\(field.key): latest=\(stats.latest) min=\(stats.minimum) max=\(stats.maximum) mean=\(stats.mean) count=\(stats.count)"
            }.joined(separator: "; ")
            let known = OuraRawInstruments.catalog.first { $0.key == capture.channel }
            let state = known.map { "\($0.acquisition) verified=\($0.verified)" } ?? "record raw-only"
            let detail = "\(state) | \(capture.context) | length=\(capture.wire.count) ringTick=\(capture.ringTick.map(String.init) ?? "unavailable") | hex=\(bytes) | decimal=\(capture.wire) | text=\(OuraRawInstruments.readable(capture.wire)) | \(fields) | stats \(summaries)"
            if jsonOut { jsonLines.append(JSONLine(index: n, kind: capture.channel, detail: detail)) }
            else { print("[\(n)] \(capture.channel) \(detail)") }
            decodedCount += 1
        }
        if captures.isEmpty && !jsonOut { print("[\(n)] no safe complete raw event") }
        continue
    }
    if liveMotion || raw.first == 0x33 {
        if liveMotionSummary && rec.tsMs == nil { die("--live-motion-summary requires a host-receipt ts_ms for every capture record") }
        let frames = OuraFraming.parseOuterFrames(raw)
        let complete = !frames.isEmpty && frames.reduce(0) { $0 + $1.totalLength } == raw.count
        let packets = complete ? frames.compactMap { OuraLiveMotion.decode($0) } : []
        if packets.isEmpty && !liveMotionSummary {
            if jsonOut { jsonLines.append(JSONLine(index: n, kind: "undecoded", detail: "invalid or unsupported live motion notification")) }
            else { print("[\(n)] (no live motion)") }
        }
        for packet in packets {
            if liveMotionSummary, let epochMs = rec.tsMs {
                for xyz in packet.samples {
                    let next = OuraLiveMotionSummary.sample(epochMs: epochMs, xyz: xyz)
                    motionSummaries[next.ts] = try motionSummaries[next.ts]?.merge(next) ?? next
                }
                continue
            }
            let vectors = packet.samples.map { "(\($0.x),\($0.y),\($0.z))" }.joined(separator: ";")
            let detail = "LIVE_MOTION metadata=\(packet.metadataByte) sequence=\(packet.sequence) xyz=\(vectors)"
            decodedCount += 1; tagCounts["LIVE_MOTION", default: 0] += 1
            if jsonOut { jsonLines.append(JSONLine(index: n, kind: "LIVE_MOTION", detail: detail)) }
            else { print("[\(n)] " + detail) }
        }
        continue
    }
    let events = driver.ingest(notification: raw, reassembler: reassembler)
    if events.isEmpty {
        // Show the raw tag so an undecoded record is visible (the RE worklist), without guessing.
        let tagHex = raw.first.map { "0x" + String($0, radix: 16) } ?? "?"
        tagCounts["undecoded(\(tagHex))", default: 0] += 1
        if !jsonOut {
            print("[\(n)] gen=\(gen.rawValue) tag=\(tagHex) -> (no event)")
        } else {
            jsonLines.append(JSONLine(index: n, kind: "undecoded", detail: tagHex))
        }
        continue
    }
    for e in events {
        decodedCount += 1
        let line = describe(e)
        let key = line.split(separator: " ").first.map(String.init) ?? "?"
        tagCounts[key, default: 0] += 1
        if jsonOut {
            jsonLines.append(JSONLine(index: n, kind: key, detail: line))
        } else {
            print("[\(n)] " + line)
        }
    }
}

if liveMotionSummary {
    var summaries: [[String: Any]] = []
    for summary in motionSummaries.sorted(by: { $0.key < $1.key }).map(\.value) {
        var object = try JSONSerialization.jsonObject(with: Data(summary.payloadJSON().utf8)) as! [String: Any]
        object["ts"] = summary.ts
        for axis in ["x", "y", "z"] { object["mean_\(axis)"] = summary.mean(axis) }
        summaries.append(object)
    }
    let data = try JSONSerialization.data(withJSONObject: summaries, options: [.sortedKeys])
    print(String(decoding: data, as: UTF8.self))
} else if jsonOut {
    let enc = JSONEncoder()
    enc.outputFormatting = [.prettyPrinted, .withoutEscapingSlashes]
    if let data = try? enc.encode(jsonLines), let s = String(data: data, encoding: .utf8) {
        print(s)
    }
} else {
    var summary = "\n\(decodedCount) events from \(total) records (tier-b \(allowTierB ? "on" : "off"))\n"
    for (t, c) in tagCounts.sorted(by: { $0.value > $1.value }) {
        summary += "  \(pad(String(c), 5, right: true))  \(t)\n"
    }
    FileHandle.standardError.write(Data(summary.utf8))
}

if rawEvents && !jsonOut {
    let window = rawWindow.snapshot()
    print("raw window retained=\(window.records.count) evicted=\(window.evictedRecords) bytes=\(window.evictedBytes) values=\(window.evictedValues) oversized=\(window.truncatedRecords)")
}
