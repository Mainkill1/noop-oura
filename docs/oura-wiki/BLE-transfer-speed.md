# BLE transfer speed

[Index](Home.md). Faster history sync is plausible, but there is no established “raise
Bluetooth rate” command for this Gen5. Sensor acquisition rate and backlog download speed
are separate. Android 12.5.0 adds standard host-side transfer preferences and measured diagnostics; it does not raise sensor acquisition rates.

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
the callback or request rejection. Android 12.5.0 additionally requests high connection priority
once per actual history drain, through the connection owner. When that drain finishes, yields to
Live, or is cancelled, the owner requests balanced operation. Authentication and the history cursor,
page/quiet-boundary rules, decoding and storage behavior stay unchanged.

The owner reads the actual PHY once per connection. With local 2M support and a successful uncoded
baseline read, it may request 2M during history, then restore the baseline preference at exit. A coded
or unknown baseline is left alone. Rejected/missing requests do not block ordinary history. PHY
callbacks report actual observed TX/RX values; neither a submitted PHY preference nor an accepted
priority request proves negotiation succeeded. A late callback cannot restart an ended boost.

A five-minute **transport boost lease** also restores preferences if a history operation becomes
silent. It does not end history, mark data saved, or impose a time limit on Live. Handler scheduling
uses uptime, so every connection-executor entry also checks monotonic elapsed time to release an
expired lease on the first work after device sleep. Repeated connection callbacks retain the same
owner; retired-GATT callbacks cannot alter the current connection.

This is Android-specific host policy. CoreBluetooth offers no equivalent public priority/PHY controls
here; there is no change to cross-platform stored values or signal formulas.

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

## Transfer recap in Android 12.5.0

Selecting a tile or graph opens inspection details with the latest observed transfer for that selected
ring. Raw and Live inspection also expose this recap. It is held in memory for the current source;
restarting the app or changing source clears it. It is **not** a per-record download timestamp.

The recap distinguishes completed, yielded, interrupted and disconnected windows. It records host
start/end times, monotonic duration, received bytes, average wire bytes/second including quiet time,
notification count/maximum size, coarse payload-size counts (≤20 / 21–100 / >100 bytes), complete
outer frames and notifications holding multiple outer frames, record envelopes and events decoded
from those record envelopes. Traffic includes control replies; these are **not unique persisted
sample counts**. Separate secure/battery response callbacks do not contribute to the record-event
counter. The existing local-storage load timestamp remains a separate fact.

The recap also reports actual negotiated MTU and observed TX/RX PHY, boost intent, high/balanced
priority request acceptance and PHY restoration submission. Unsupported or unobserved values stay
unknown. Rejected restoration remains visible in diagnostics; it causes no retry storm or reconnect.

No speed gain is promised until comparable useful-goodput and energy evidence exists. Undocumented
bundling, newer envelope history and ring-side policy writes remain research candidates: their
reversal or full framing contracts have not been validated. No such proprietary writes were added.

Design informed by the public Android API contract and the separately investigated Oura 7.27.0
behavior report. No vendor implementation, source identifiers or assets were carried into NOOP.


## First Android 12.5.0 device check

On the user's Gen5 and OnePlus 15, a 6.691-second history window completed with 401 notifications,
6,561 incoming bytes and a largest notification of 20 bytes, despite MTU 247. There were 401 complete
outer frames, no notifications containing multiple outer frames, 391 record envelopes and 744 events
decoded from those envelopes. These are traffic/window counters, not unique persisted rows or a
controlled performance comparison.

Android accepted both the high-priority and balanced requests. A successful baseline PHY read
reported TX/RX 1M. The 2M preference update returned GATT status 6; the restoration preference also
returned status 6. Neither callback proves a successful PHY change, so the recap retained the last
successfully observed 1M/1M state. Ordinary history completed despite the unsupported tuning outcome.
A later link timeout prevented the initial Live re-entry check; this is separate from transfer
completion and no causal attribution to tuning is established.

The observed small notification ceiling makes packing a stronger lead than simply raising the MTU.
It does not prove which bundling/policy candidate the firmware supports or that those writes are
reversible. Those candidates remain excluded from automatic traffic.
