# Oura live-session wire and storage contract

This is raw instrumentation in the default-off Experimental Oura client. It does not
calibrate acceleration, feed scoring, infer ring timestamps, or claim combined
HR/ACM capacity. A target Gen5 capture produced valid 14-byte motion bodies at approximately
49 vectors/second; continuous renewal and lifecycle acceptance still require hardware verification. New reversible command coordination
is recorded in PR #1 because this fork disables issues.

## Provenance and scope

Public documentation consulted on 2026-10-14:

- [open_oura live-channel documentation](https://github.com/Th0rgal/open_oura/blob/e57c43ce5fe0cf9a646afa790bda3731be767507/crates/README.md):
  ACM mask 0x20, duration in minutes, explicit OFF, reported ~50 Hz observation.
- [open_oura Ring 3 notes](https://github.com/Th0rgal/open_oura/blob/e57c43ce5fe0cf9a646afa790bda3731be767507/docs/horizon-ring3-protocol-cheatsheet.md):
  literal OFF and ACK; accepted guesses did not produce samples. Acceptance alone is not streaming.
- [open_ring protocol specification](https://github.com/LogosIsLife/open_ring/blob/master/PROTOCOL.md):
  14-byte 0x33 bodies with two signed little-endian XYZ vectors; these bytes are not ring time.
- Parent investigator independently stated request field offsets and widths from
  public protocol declarations/comments in [open_oura protocol.rs](https://github.com/Th0rgal/open_oura/blob/e57c43ce5fe0cf9a646afa790bda3731be767507/crates/oura-protocol/src/protocol.rs)
  and the measurement-format comment in [client.rs](https://github.com/Th0rgal/open_oura/blob/e57c43ce5fe0cf9a646afa790bda3731be767507/crates/oura-link/src/client.rs).
  The public reference is MIT licensed at that commit. The implementer received a fact-only contract, no reference implementation expression.
  Facts are attributed unvalidated candidates under CONTRIBUTING's facts-versus-code rule.

## Reversible commands

Each wire Start requests one minute, with no delayed start:
`06 07 20 00 00 00 01 00 00`. After outer tag and length, the body is
mask:u32LE, durationMinutes:u16LE, delay:u8. No RData/flash collection is started.

Stop clears the realtime mask: `06 04 00 00 00 00`. It runs on explicit Stop,
background/leave, teardown, and after authentication to clear a previous process's
residual realtime state. While current foreground page demand remains active, the central
serialized queue renews the finite lease after 50 seconds from actual write submission.
At most one renewal can be pending or outstanding; UI calls cannot accelerate it.
Queue congestion can delay renewal past ring expiry, leaving a gap; finite duration
remains the crash/lost-OFF safety. Duration zero is not used. Link loss/process kill
cannot guarantee delivery of OFF.

Response `07 01 <status>`: zero means accepted; nonzero means rejected.
Neither status advances the receiving phase without valid samples.

Existing HR Start uses `2f 03 22 02 03`, `2f 03 26 02 02`. Android page/authentication/teardown
cleanup restores automatic daytime HR with `2f 03 22 02 01`, then unsubscribes with
`2f 03 26 02 00` (OURA_PROTOCOL §5.6). Mode 1 keeps background measurements enabled;
subscription 0 stops phone notifications. Both writes pass through the serialized worker.
The explicit `liveHRDisable` command remains the accurate mode-0 protocol operation, but the
automatic Android queue rejects that payload at admission and submission, regardless of label.
The documented connected-live auto-revert cannot replace explicit restoration: on the target
Gen5, a read-only status probe still reported mode 3 several minutes after unsubscribe alone.
HR keepalive only runs while HR is selected by the active page demand. These existing HR mode
operations and their correction are coordinated in PR #1 before implementation; selecting Live
reverses cleanup with mode 3 and subscription 2. No other feature modes are enabled.

## Notification contract

Outer tag 0x33 body length must be exactly 8 or 14. Body byte 0 is opaque metadata
(the sources disagree on its meaning); byte 1 is a sequence byte. Bytes 2..7
are X/Y/Z signed16 LE. Bytes 8..13, if present, are a second XYZ sample. Preserve
order, signed extrema, opaque metadata, and sequence. No guessed sample spacing,
Hz field, orientation transform, or g conversion. Timestamp both samples at host
monotonic receipt and retain their sample indices. Observed throughput comes from
actual received samples. Historical 0x47 summaries never enter these buffers. Raw vectors
remain in bounded rolling session memory (60 seconds / 6,000 vectors). All accepted
vectors also contribute to one-second persistent summaries, described below. Existing
HR persistence and ring-banked movement history continue separately.

Oura's TLV protocol has no documented inbound CRC; exact envelope lengths and
supported body lengths gate this path. Unknown/truncated/extra bytes are rejected.

Hardware checks must confirm varying raw axes and valid notification counts,
independent HR/motion status, explicit OFF stopping samples, lifecycle teardown,
and history yielding/resuming. Receipt-to-display under 250 ms remains unmeasured.

## Workload ownership

UI visibility/selection intents use Start/Update/Stop to set one desired selection in `OuraLiveWorkload`. A single
main-loop worker reconciles it at a fixed 150 ms settling interval; repeated Start
is idempotent and Stop cancels pending work immediately. Removed channels invalidate
pending enables as soon as their intent is accepted. The serialized queue preserves
retained channels and already queued OFFs; only added channels start after settling.
An accepted write completes before the necessary OFFs and new enables. Healthy
retained channels keep receiving and retain their first-sample timer while another
channel changes. Activity lifecycle observation covers pending Start as well.
A stopped or superseded generation cannot feed session buffers. Generation is an
opaque token with a process-unique source-incarnation namespace, so recreating the
same registry source does not reuse ownership. Requests carry the expected source
id and generation; the pre-Start generation remains an owner alias until Stop, so
immediate screen disposal can cancel a pending Start safely. Selection changes
retain session buffers, watchdog state and lease timing; they do not recreate a session.
History finishes its current page, flushes progress at its quiet boundary, then yields.
It resumes from the durable cursor only after live OFF writes complete locally,
through a current-queue and generation-checked completion barrier. A pending Start
cancels that resume. No pause opcode is sent.

Motion gets ten seconds for its first sample; a silent motion channel is marked unavailable and
disabled once without automatic retry. Selected HR continues waiting and keeps its existing paced
15-second renewals while the page remains visible. A late first pulse is accepted. A ten-second
quiet interval does not establish permanent HR unavailability or cancel the user's request.
A new session clears motion failure state. The final buffered HR batch is flushed on live exit,
even while subscription shutdown callbacks are still pending.
Stop/Start preserves queued OFF ordering before a new Start. Local write completion
is not a ring-side delivery guarantee. Session status remains grounded in actual samples.

## Persisted host motion

`OURA_LIVE_MOTION` uses the existing event key `(deviceId, ts, kind)`, with `ts` in
Unix seconds. It is distinct from `OURA_MOTION` (ring-banked signed-byte ×8 motion).
The payload contains `count`, `sum_x/y/z`, `min_x/y/z`, `max_x/y/z`,
`first_receipt_ms`, `last_receipt_ms`, `provenance="host_receipt"`, and
`units="raw_signed16"`. Means are sums divided by actual vector count; two vectors
in one packet count twice. First/last information describes receipt times, not
inferred sample spacing or ring time. The source anchors wall-clock epoch milliseconds
and monotonic elapsed time once per session; each notification's captured monotonic
receipt maps through that anchor, so later wall-clock adjustments do not retime it.

One bounded accumulator holds the current second. A later second, the periodic
worker, Stop, backgrounding or teardown flushes it through the existing persistence
sink. Each contribution is submitted once. Partial sessions sharing a second merge
count/sums/extrema and receipt bounds inside the database transaction on both platforms;
concurrent contributions cannot overwrite one another. These are additive contributions,
not replay-idempotent historical rows. Host motion does not advance the ring-history cursor.
No migration or deletion of existing records is required.

Summary payloads use a fixed flat schema with exact unescaped keys and integer JSON
literals. Whitespace and property order may vary; missing/duplicate/extra fields,
Boolean/string/floating values, and integers outside signed64 are rejected. Receipt
bounds must be ordered in the event's second. Extrema must fit signed16 and be
consistent with count and sums (a singleton has one value and one receipt time).
Count is bounded by `Int64.max / 32768` solely to keep raw signed16 sums representable;
this is not a sensor-rate assumption. Merge validates both inputs, checks arithmetic,
and fails recoverably before replacing a row. Database errors roll back the complete
batch, preserving prior bytes. Invalid persisted motion makes dashboard loading report
incomplete history instead of silently dropping that row.


`OuraDashboardData.liveMotionSeconds` exposes the most recent 120 summaries for
inspection; storage keeps every collected second. Separate graph ids are
`live_motion_x/y/z` (per-second means), `live_motion_x/y/z_min`,
`live_motion_x/y/z_max`, and `live_motion_count`. Their raw signed16 units cannot be
compared with the old `motion_x/y/z` metrics as a shared calibration.

## Offline capture replay

The platform-pure CLI prints canonical finite Start/OFF bytes without reading stdin:

```sh
swift run --package-path Packages/OuraProtocol oura-decode --live-motion-commands
# acm_start 060720000000010000
# acm_stop 060400000000
```

The foreground HR entry and normal background restoration commands can also be inspected:

```sh
swift run --package-path Packages/OuraProtocol oura-decode --live-hr-commands
# dhr_enable 2f03220203
# dhr_subscribe 2f03260202
# dhr_automatic 2f03220201
# dhr_unsubscribe 2f03260200
```

No command is sent; this executable has no BLE transport. Captured notifications can
be decoded as text or JSON:

```sh
swift run --package-path Packages/OuraProtocol oura-decode --live-motion --json --hex 330831fe0080ff7fffff
swift run --package-path Packages/OuraProtocol oura-decode --live-motion capture.json
```

A capture is an array of `{"hex":"...","ts_ms":...}` objects (`ts_ms` is optional
provenance). Entire notification framing is checked before any live vector is emitted.
Opaque metadata and sequence stay raw; no sampling-rate or calibration interpretation
is applied. Existing historical record decoding remains available without `--live-motion`.

Timed JSON captures can be decimated offline with `--live-motion-summary [FILE]`.
This exports the same canonical count/sums/extrema/receipt payload plus `ts` and
`mean_x/y/z`. Every input notification must carry its actual host-receipt `ts_ms`;
missing timestamps are an error, so hex-only samples never acquire fabricated times.

## Android HR recording correction

The 12.4.0 release sent daytime-HR mode off at authentication and on live exit. Its first-sample
watchdog also disabled HR after ten seconds and rejected later pulses for that session. On the
rooted OnePlus 15, a successful authentication was followed by that exact start/cutoff sequence;
the store contained older HR but none in the latest fifteen minutes. The investigation exported
only counts, age and known control-state labels, with no ring credentials or biometric values.

Regressions pin late first-sample acceptance, ordered automatic-mode restoration and unsubscribe,
repeated exit/re-entry, immediate flushing of the final buffered HR, and rejection of feature-wide
mode 0 even when a queued payload is mutated. The pure automatic-mode helper is byte-identical in
Swift and Kotlin; executable Swift CLI output is pinned verbatim in the Kotlin command test.
No decoder math, stored values, or database schema change. Android orchestration uses this helper;
Apple transport lifecycle is separate and is not changed or claimed as hardware-verified here.

An initial Android candidate without the ten-second cutoff recorded 22 new HR rows on the target
ring. Its first valid HR arrived nine seconds after live entry; synthetic coverage accepts a first
pulse at 45 seconds. Unsubscribe-only cleanup left the ring in forced live mode 3 for minutes,
which motivated explicit automatic-mode restoration. Final mode-1 read-back and repeated page/task
switching are pending; these interim observations do not establish that background recording has recovered.

The corrected build passes 6,837 Android JVM cases (zero failures/errors, six existing skips),
280 Swift OuraProtocol cases and 347 Tools cases, including the parity acceptance suite after
the ordinary derived twin-authority refresh. Signed Full Release assembly and vital lint pass
for 12.4.1 (566); its certificate matches 12.4.0. Independent scoped review found no production
blocker. No Apple transport build or hardware verification is claimed.

The corrected release is installed in place on the OnePlus 15, with unchanged encrypted ring
preferences and UID. Authentication succeeded, a new Live session received valid HR, and stored
HR rows increased after exit. Control logs show ordered automatic-mode restoration then
unsubscribe. Wireless ADB disconnected before the final ring-mode read-back; local command
completion is not represented as proof of restored ring-side automatic operation.


## Live receipt timing and burst inspection

Android 12.5.0 build 568 retains notification ordinal and callback-to-owner timing alongside each
volatile Live HR sample. The details popup reports receive-gap ranges, readings per notification,
and local callback queue wait over the retained up-to-60-second window. Unknown metadata stays
unknown; these diagnostics do not change protocol requests, stored data or HR calculation.
The graph's time axis names phone receipt time. Several readings can share a callback, and separate
callbacks can cluster without establishing measurement times. No guessed cadence is imposed.

The official 7.27.0 static review identified a uint16LE candidate age at Live body 3–4, but no units,
reference instant or sentinel behavior. The reviewed app does not use it to reconstruct Live time.
It is documented as raw evidence, not decoded as milliseconds or subtracted from receipt. See
[Live HR timing and delivery bursts](oura-wiki/Live-HR-timing-and-bursts.md).

On the rooted OnePlus 15/Gen5 with build 567, Live resumed after the ring returned to range, produced
fresh HR and persisted rows, stopped on Home with automatic-mode restoration/unsubscribe, and
started a second receiving session on return. The ring then reported automatic mode 1 after leaving
Live through a read-only feature-status query. This verifies the cleanup on hardware; it does not
validate the candidate age or establish the cause of the separately reported delivery burst.


Build 568 validation: all 6,851 Android JVM cases pass (six existing skips), signed Full Release
assembly/vital lint and Full Debug/instrumentation builds pass, and all 23 selected synthetic phone
UI cases pass, including the delivery popup at large text sizes. Independent delivery review has no
blocking findings. Actual GATT-to-session-to-popup propagation was then observed on the Gen5:
21 readings/21 notifications, largest group one, receive gaps 0–7,629 ms and maximum local callback
queue wait 2 ms. The end-session log matched the popup. This demonstrates burst arrival evidence;
it does not establish the sensor's measurement timestamps or Bluetooth transit latency.


Final build 568 phone checks also observed automatic restart of the Live request on returning from
Home and fresh receiving after a later Live re-entry. The first observation at about ten seconds
had no received value; the next observation showed a two-second-old receipt. The page remained
active through that delay. After an earlier build 568 Live exit, the read-only status query returned
mode 1 (automatic). The existing encrypted key preferences and release UID remained unchanged.

## Inline chart inspection and time zoom

The Android Oura graphs share local presentation controls: tap or horizontal single-finger drag
inspects raw observations inline; pinch zooms the time axis; two-finger translation pans. A vertical
single-finger gesture remains available to scroll the page. Graph inspection does not open a modal.
Dedicated title/details controls retain acquisition, summary and delivery diagnostics.

Visible y scales identify their quantities and units with lower/middle/upper grid values. The first
two comparison quantities use edge rails; any additional distinct scales list their named grid values.
Compatible XYZ channels share one scale. X labels follow the visible record-time range for History
and phone-receipt time for Live/Raw. Zoom changes neither sampling demand nor stored data and does
not invent intermediate observations. Reset returns to the selected full window and Live following.

Skin temperature remains a recorded metric. The official-APK follow-up found a raw temperature-related
HR tail, but no validated unit, calibration or skin origin; see the
[candidate contract](oura-wiki/Official-app-protocol-candidates.md#live-hr-temperature-tail-is-not-calibrated-skin-temperature).
That quantity is not represented as a calibrated Live measurement.


Android 12.5.1 build 569 validation: 6,860 JVM cases passed (zero failures/errors, six existing
skips), all 31 selected synthetic UI cases passed on the OnePlus 15, and signed Full Release
assembly/vital lint plus Full Debug/instrumentation builds passed. Phone fixtures cover inline
tap/drag without a dialog, genuine two-pointer pinch/pan/reset, visible time-range changes,
vertical page scrolling, mixed-unit ticks at 2× text, held Live timing and Raw gaps. Retention
regressions include count-cap eviction and preservation of another selected signal. Repository
tools, parity acceptance, localization and source hygiene passed; independent review has no
remaining source blocker. The signed update retains the existing UID and byte-identical encrypted
ring preferences; authentication and a normal history completion were observed after installation.
No new BLE command, physiological conversion, stored value or schema was introduced. The
existing source-retention constants are shared with the Android charts without changing their values.

The installed signed build also passed actual Oura Live Health tap/drag checks without opening a
dialog. The phone displayed the recorded-temperature label and receipt-time axis, and later reported
a zero-second-old Live receipt. The original screen timeout was restored and the debug app disabled.
