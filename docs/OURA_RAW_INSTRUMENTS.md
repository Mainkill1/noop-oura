# Oura raw instruments

The Android Oura dashboard's **Raw** category is an instrument view for received sensor
records and a small set of existing read-only queries. It does not add a general command
sender, change authentication, or calculate health scores from research signals.

## Navigation and displays

Select **Oura → Raw**, then choose an event and field. An event's acquisition label distinguishes
live notifications, banked records downloaded by normal synchronization, and read-only polling.
Selecting a banked event does not turn it into a live sensor or restart the history download.
Imports are separate sources and cannot initiate ring work.

Display choices are **Graph**, **Gauge**, **Numbers**, and **Bytes**. Numeric summaries describe
the retained observation window, including its count, latest value, minimum, maximum and mean.
A gauge uses an explicit observed range; it is not a clinical target. Graphs use actual receipt
time and identify their fields and units. Smoothing changes the display only. Live movement can
show X, Y and Z together, retaining the original values for inspection.

The byte view shows the received event's bytes as hexadecimal, decimal bytes and escaped text.
It identifies the framing context and byte length. Byte fields remain available when a typed
layout is unverified. Authentication frames, keys, nonces and proofs are excluded before capture.
Debug and control records are not a general-purpose raw capture channel. Incomplete notifications
are rejected rather than joined across callbacks: the existing parser deliberately avoids that
join after earlier phantom-record failures. A genuinely fragmented research event will remain
unavailable until its continuity rules can be validated.

Raw evidence is bounded, volatile diagnostics on the phone: up to 256 records, 64 KiB of wire
bytes and 32,768 numeric values across channels. It is not a complete packet archive.
Window limits and eviction indicators describe what remains available. Leaving the app stops
foreground polling and live demand; the existing decimated live HR and movement history continues
to use its established storage path. Raw byte evidence is not exported automatically.

## Timing and workload

Read-only query presets use existing battery and feature-status commands. The interval picker
offers 250 ms, 500 ms, 1 s, 2 s, 5 s, 10 s and 30 s. The worker waits for the preceding request
to finish before applying the next delay. It does not accumulate missed polls or transmit a burst
to catch up. Channel, interval and page-ownership changes preserve the latest completion boundary
on the same connection, so repeated navigation cannot shorten the requested interval. UI changes
replace desired work; they do not create another worker.

The display separates queue wait, local GATT write completion, observed request-to-response time,
and the arrival interval of pushed packets. Local write completion does not establish ring delivery.
Oura replies have no per-request identifier, so matching opcode/feature replies provide observed
timing rather than proof of unique correlation. A timed-out ambiguous request is not blindly replayed.

Push streams retain their ring-defined cadence. Changing a polling interval cannot make an oxygen,
temperature or other banked event arrive live. Live HR and accelerometer demand still passes through
the central guarded lifecycle helper, which stops it when the view leaves the foreground.

## Event interpretation

The selector catalogs the existing event tags, including channels without a validated numeric
decoder. A catalog entry is not a promise that this firmware emits the event.

| Event | Instrument interpretation | Limits |
| --- | --- | --- |
| `0x80` | Daytime green-channel heartbeat intervals and quality bits | Raw inspection retains low-quality observations; production HR filtering remains separate. |
| `0x60` | Nighttime heartbeat intervals and optical amplitudes | Optical amplitude is an instrument value, not a calibrated health metric. |
| `0x8B` | Research oxygen optical record | R-ratio/perfusion descriptions exist, but the header offset is unresolved. Bytes remain available without a guessed SpO₂ percentage. |
| `0x46` | Recorded temperature fields | Uses the existing decoder and units. Receipt time remains distinct from ring time. |
| `0x47` | Recorded motion fields | Its banked layout differs from live signed16 accelerometer data. |
| `0x5D` | HR and RMSSD summary pairs | Describes ring summaries, not a newly computed HRV score. |
| `0x81` | Research raw optical PPG record | Public delta/state descriptions disagree; raw bytes are available without an asserted cardiovascular-age result. |
| `0x2F/0x28` | Live HR notification | Only the allowed sensor suboperation is captured. |
| `0x33` | Live X/Y/Z accelerometer notification | Raw counts; no validated conversion to g. |
| `0x0D` | Battery response | Read-only query response. |

Continuous calibrated live SpO₂ is not established by these protocol references. Oxygen sensing
configuration, optical R-ratio, perfusion, historical raw oxygen and calibrated imported oxygen
percentages are different facts and must remain separately labeled.

## Independent design and provenance

The initial dashboard had HR and XYZ live plots but no event/field selector, alternative instrument
display, response byte viewer or request timing. Those gaps justify this addition without replacing
the stable connection, authentication or history cursor machinery.

Protocol research used public documentation. An investigator wrote independent behavioral requirements;
a separate implementer used those requirements and repository-owned APIs. No foreign implementation,
visual assets, prose or component structure was copied. Required convergence is limited to wire
identifiers, units and interoperability facts.

- [open_oura client guide](https://github.com/Th0rgal/open_oura/blob/e57c43ce5fe0cf9a646afa790bda3731be767507/crates/README.md),
  pinned at `e57c43ce5fe0cf9a646afa790bda3731be767507`,
  [MIT license](https://github.com/Th0rgal/open_oura/blob/e57c43ce5fe0cf9a646afa790bda3731be767507/LICENSE):
  distinguishes banked events from live streams and describes research optical records.
- [open_ring public protocol specification](https://github.com/LogosIsLife/open_ring/blob/aa0544965b3060840cdbdc11fe5057e4606694dc/PROTOCOL.md),
  GPL-3.0, pinned at `aa0544965b3060840cdbdc11fe5057e4606694dc`, reviewed 2026-10-14: supplies documented framing and research-event facts. Its implementation
  source was not used. Conflicting layouts do not override NOOP's established decoder contracts.
- [Oura oxygen sensing help](https://support.ouraring.com/hc/en-us/articles/7328398760851-Blood-Oxygen-Sensing-SpO2):
  documents sleep-related sensing rather than a guaranteed continuous live percentage stream.
- [NOOP protocol notes](OURA_PROTOCOL.md) and [live wire/storage contract](OURA_LIVE_PROTOCOL.md):
  establish the target's existing commands, decoding, raw units and persistence boundaries.

Undocumented calibration, guessed payload offsets, receipt-time substitution for unanchored ring
timestamps and packet-arrival intervals presented as request latency were deliberately rejected.
The new Android view does not claim native Apple UI or transport parity. Shared pure behavior has
independent Swift/Kotlin implementations; the offline Swift decoder remains a real inspection consumer.

## Validation

The final Android JVM run covers 6,829 tests with zero failures/errors and six existing skips.
Swift OuraProtocol passes 279 tests, including a standalone exhaustive 65,536-pair quality oracle
whose output is pinned in Kotlin. The 161 core Tools checks pass, including eight offline decoder
CLI checks. All 50 parity acceptance checks pass. Full, Demo and instrumentation APKs build;
localization and documentation checks pass. Independent task review and the scoped pacing-fix
re-review approve the implementation. The fix adds completion-based cadence tests for interval,
channel and ownership changes; 130 focused Oura transport/UI regressions also pass.

The signed Full Release APK is version 12.4.0 (565), package `com.noop.whoop`, with a dedicated
RSA-4096 release certificate. APK signature verification and release vital lint pass; the manifest
is not debuggable. The certificate SHA-256 fingerprint is
`ef5afed31777d112c97aaad0a633c354fe83151fa63b2d36e76ce6f2c71d1a9a`.
Private signing materials are excluded from the repository and release assets.

On the rooted OnePlus 15, the release APK is installed alongside the retained development package.
A phone-only backup preserves the source APK, data and encrypted key preferences. Databases were
compared byte-for-byte after copying, and all four stored ring keys were re-encrypted under the
release app UID and verified in memory. The temporary plaintext transfer file was removed; real
credentials and health data never left the phone. No reset, takeover or ring-key replacement was used.

The first final synthetic device run executed 34 cases: 33 passed. The remaining Raw navigation
clearance assertion measured visibility before reaching the scroll end. Its fixture now consumes
the trailing padding, requires the true scroll end and retains the original unclipped physical
clearance assertion. The instrumentation APK compiles. Its focused rerun and fresh release-app
authentication await the phone being unlocked. Earlier development builds authenticated with the
existing ring key; those results are not represented as release-app authentication. Continuous
motion lease renewal and new raw polling/cadence checks remain unverified on hardware.

Apple app targets are unavailable in this Linux environment; native Apple UI/transport parity is
not claimed. The fork currently reports no active GitHub Actions workflow roster, so local results
are not represented as remote CI checks.
