# BLE transfer speed

[Index](Home.md). Faster history sync is plausible, but there is no established “raise
Bluetooth rate” command for this Gen5. Sensor acquisition rate and backlog download speed
are separate. This documentation changes neither.

## Independent controls

| Control | Can affect | Cannot establish by itself |
| --- | --- | --- |
| ATT MTU | Maximum application value size per ATT packet | That the ring fills packets or sends more frequently |
| Link-layer data length | Payload efficiency below ATT | A new sensor rate or available historical records |
| LE 1M/2M PHY | Radio airtime | Twice the application throughput, or support by this ring |
| Connection interval/event budget | How often and how long the peers exchange packets | A guaranteed interval merely from an accepted API request |
| Notification packing | Records carried in each notification | A different radio PHY |
| History page size and quiet boundary | Application overhead and safe continuation | Permission to overlap unfinished batches |
| Phone parsing/persistence | How quickly received records become saved data | More radio capacity |

Android exposes MTU negotiation, connection-priority requests and PHY preferences. For bulk
transfer, high priority can be requested, then balanced restored. PHY preference is advisory;
inspect the resulting callback. From Android 14 the first GATT MTU request asks for 517 and
later requests are ignored; use the negotiated result, not the requested argument.
[Android BluetoothGatt API](https://developer.android.com/reference/android/bluetooth/BluetoothGatt).

LE 2M support is optional. Its nominal radio rate is not application goodput. A supported
2M link may help bulk transfer, while conditions and packet overhead still determine the
result. [Bluetooth SIG LE primer](https://www.bluetooth.com/bluetooth-le-primer/).

For example, an ATT MTU of 247 permits a notification value up to 244 bytes. A ring that sends
only 20-byte values does not use that space. Increasing the requested MTU further does not
force aggregation. Link-layer fragmentation is also distinct from app-level record framing.

## Current NOOP baseline

Android requests the generation-specific MTU during service discovery and continues from
the callback or request rejection. It does not currently request a high-priority connection
or explicitly select 2M PHY in `OuraLiveSource`. Android and its controller can still negotiate
parameters independently; absence of an app call does not prove a 1M connection.

The existing queue permits one outstanding local GATT write, never blindly replays an
uncertain write, and lets the ring stream a page before continuation. Reducing these guards
is not a demonstrated throughput optimization.

## Strongest existing performance clue

The repository records an earlier Gen3/iOS comparison at the **same negotiated MTU 203**:
NOOP history notifications held one small record, while the official app's notifications
often packed approximately ten records into 196–200-byte values. The recorded estimates
were approximately 2.1 KB/s versus 19 KB/s at similar notification frequency.
[Existing capture account](../OURA_PROTOCOL.md#23-inner-event-record-tlv---the-event-stream).

This is earlier-capture evidence, not a benchmark of the user's Gen5 or the current Android
release. It demonstrates a packing difference; it does not identify the cause. The earlier
session differed in notification mask and contained a BLE-mode operation whose effects are
unresolved. Current APK inspection adds a distinct bundling negotiation and lower-latency
ring-policy candidate; see [the candidate contracts](Official-app-protocol-candidates.md).
None establishes support, reversal or useful throughput on this Gen5. NOOP already exposes a separately gated notification-mask experiment in Test Centre.
Do not turn that observation into an automatic unknown mode write.

The first useful distinction is therefore **small packets despite a large MTU** versus
**large packets limited by radio/event scheduling** versus **fast receive but slow storage**.
Each implies a different change.

## Measurements that answer the speed question

Record session metadata and aggregates rather than personal payloads:

- Phone OS, ring generation/firmware, negotiated MTU, resulting TX/RX PHY, and connection
  interval/latency/timeout where observable; say when a value is unavailable.
- Notification count, payload-size histogram, total wire bytes, records per notification,
  valid/unknown/rejected record counts and ring remaining-byte summaries.
- Active receive duration and complete drain duration separately, including quiet-boundary
  time, local queue wait, parse time, database flush time and interruption reason.
- Unique persisted records and unresolved-anchor counts, so a faster repeated dump cannot
  masquerade as useful throughput.

Use monotonic duration. Useful goodput is unique valid persisted data divided by elapsed
transfer time. Also retain wire bytes/second and records/second: record sizes vary. Comparing
different backlogs or a first full pull against a near-empty incremental pull is misleading.

A future speed option should be scoped to a bulk-history session, request standard supported
parameters once through the owner, restore balanced operation on exit, and fall back cleanly.
It must preserve authentication, framing, cursor and storage behavior. No gain is promised
until repeatable useful-goodput and energy evidence exists. Undocumented proprietary BLE/ring
mode writes remain research candidates, not shortcuts.
