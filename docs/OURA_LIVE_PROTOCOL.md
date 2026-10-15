# Oura explicit live-session wire contract

This is raw instrumentation in the default-off Experimental Oura client. It does not
calibrate acceleration, feed scoring, infer ring timestamps, or claim combined
HR/ACM capacity. Hardware validation is pending. New reversible command coordination
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

Explicit Start requests one minute, with no delayed start:
`06 07 20 00 00 00 01 00 00`. After outer tag and length, the body is
mask:u32LE, durationMinutes:u16LE, delay:u8. No RData/flash collection is started.

Stop clears the realtime mask: `06 04 00 00 00 00`. It runs on explicit Stop,
background/leave, teardown, and after authentication to clear a previous process's
residual realtime state. Local motion sessions stop after one minute; they never
renew silently. Link loss/process kill cannot guarantee delivery of OFF.

Response `07 01 <status>`: zero means accepted; nonzero means rejected.
Neither status advances the receiving phase without valid samples.

Existing HR Start uses `2f 03 22 02 03`, `2f 03 26 02 02`;
Stop uses `2f 03 22 02 00`, `2f 03 26 02 00` (OURA_PROTOCOL §5.6).
HR keepalive only runs during explicitly requested HR sessions.

## Notification contract

Outer tag 0x33 body length must be exactly 8 or 14. Body byte 0 is opaque metadata
(the sources disagree on its meaning); byte 1 is a sequence byte. Bytes 2..7
are X/Y/Z signed16 LE. Bytes 8..13, if present, are a second XYZ sample. Preserve
order, signed extrema, opaque metadata, and sequence. No guessed sample spacing,
Hz field, orientation transform, or g conversion. Timestamp both samples at host
monotonic receipt and retain their sample indices. Observed throughput comes from
actual received samples. Historical 0x47 summaries never enter these buffers. Raw live XYZ is memory-only and is
retained after Stop for inspection, and cleared on next Start or source teardown; it is not saved into the second-resolution OURA_MOTION summary
schema. Existing HR persistence and ring-banked movement history continue separately.

Oura's TLV protocol has no documented inbound CRC; exact envelope lengths and
supported body lengths gate this path. Unknown/truncated/extra bytes are rejected.

Hardware checks must confirm varying raw axes and valid notification counts,
independent HR/motion status, explicit OFF stopping samples, lifecycle teardown,
and history yielding/resuming. Receipt-to-display under 250 ms remains unmeasured.

## Workload ownership

UI Start/Update/Stop calls set one desired selection in `OuraLiveWorkload`. A single
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
retain the first motion deadline; they never silently extend or recreate a session.
History finishes its current page, flushes progress at its quiet boundary, then yields.
It resumes from the durable cursor only after live OFF writes complete locally,
through a current-queue and generation-checked completion barrier. A pending Start
cancels that resume. No pause opcode is sent.

Each requested channel gets ten seconds for its first sample. A silent channel is
marked unavailable and disabled once without automatic retry; the other channel
continues independently. A new explicit session clears the failure state.
Rapid Stop/Start retains the original motion deadline until the serialized OFF write
completes locally. This is local queue completion evidence, not a ring-side delivery
guarantee. Session status remains grounded in actual samples.
