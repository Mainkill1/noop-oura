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

Existing HR Start uses `2f 03 22 02 03`, `2f 03 26 02 02`;
Stop uses `2f 03 22 02 00`, `2f 03 26 02 00` (OURA_PROTOCOL §5.6).
HR keepalive only runs while HR is selected by the active page demand.

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

Each requested channel gets ten seconds for its first sample. A silent channel is
marked unavailable and disabled once without automatic retry; the other channel
continues independently. A new session clears the failure state.
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
