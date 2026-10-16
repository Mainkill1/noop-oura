# Live HR timing and delivery bursts

[Index](Home.md). Several HR readings appearing close together do not establish when those
measurements were acquired. The relevant clocks and packet boundaries must stay separate.

## What is known about the Live packet

NOOP subscribes to daytime-HR feature `0x02`; validated interval decoding uses the `0x2F/0x28`
push path. A subscription is different from sending a separate request for each reading. The
currently decoded interval yields one HR value per supported push body. Multiple complete frames
may share delivery; multiple notifications may also reach the phone close together.

This Live push is not a timestamped historical TLV record. The driver's contextual last-seen ring
counter is not evidence of a measurement timestamp carried in this push. Android Live plotting uses
the monotonic time captured at the notification callback, before dispatch to the source owner.

Historical records have their own ring counters and established anchoring rules. Those counters
must not be replaced by download-arrival time. Conversely, an unrelated history counter must not
be reused as the Live reading's measurement time.

## Candidate age field: static finding, units unknown

The separately investigated official Android 7.27.0 package decodes an unsigned 16-bit little-endian
value at zero-based body offsets **3–4 after sub-operation `0x28`**, with the feature identifier at
body offset 0. In a complete outer frame these are offsets 6–7. The interval remains at body 5–6.
The app attributes the preceding integer to elapsed time since the latest acquisition, but the
review found no established units, reference instant, sentinel meanings or conversion to time.

A direct-reference scan found no consumer scaling or subtracting this candidate from the phone
clock. The reviewed official Live display path discards that header and timestamps the reading on
the phone. No request ID, sequence number, absolute measurement time or Bluetooth transit-delay
field was established. Static field names or range 0–65,535 do not establish milliseconds, seconds,
validity of zero, saturation or wrap semantics. The API-version-dependent tail remains separate.

This is a candidate compatibility fact from the authorized static review, **not independently
validated on this Gen5**. Raw inspection already exposes the separate body byte fields; no numeric
age conversion or timestamp correction is enabled. Provenance is the same official package/hash
listed in [evidence and limits](Evidence-and-open-questions.md). No vendor code or identifiers were
carried into NOOP.

## Android 12.5.0 build 568 delivery details

The Live inspection popup summarizes the retained HR window, up to 60 seconds:

| Evidence | What it observes | What it cannot establish |
| --- | --- | --- |
| Reading count | Accepted readings still retained in Live memory | A count of unique physiological beats |
| Notification count / largest group | Actual source notification ordinals shared by decoded HR frames | Ring acquisition times or dropped notifications |
| Last/minimum/maximum receive gap | Adjacent captured callback times, at millisecond resolution | Command response latency or physiological beat spacing |
| Last/maximum app callback queue wait | Callback receipt to source-owner processing entry | Bluetooth transit time or UI rendering duration |
| Out-of-order receive count | Adjacent captured times that regress in processing order | Their cause or an instruction to retime them |

Unknown metadata remains unknown; identical receive times do not become a deduplication key.
The counters do not expose HR values in logs or leave the phone automatically. They do not change
stored values, request cadence, subscription ownership or history extraction.

For example, one notification containing four HR frames has four readings and one notification.
Four separate callbacks a few milliseconds apart have four notifications and a largest group of
one. Four callbacks a second apart that wait for a busy owner retain their one-second gaps and show
a larger local queue wait. A small queue wait does not rule out sensor/controller/OS buffering or
later rendering delay; those boundaries were not measured by this diagnostic.

Raw HR inspection can show the same delivery recap. The graph axis explicitly names **phone receipt
time**. It retains the actual observations and gaps; it does not spread a burst into guessed equally
spaced measurements. Request-to-reply timing on read-only battery/status queries remains separate
and only observational because those replies lack unique request IDs.

Sources: NOOP's [Live callback/session path](../../android/app/src/main/java/com/noop/ble/OuraLiveSource.kt),
[delivery summary](../../android/app/src/main/java/com/noop/ble/OuraLiveDelivery.kt), and the independent
behavior-only official 7.27.0 review dated 2026-10-15. The public
[Ring4 protocol notes](https://github.com/ringverse/protocol/blob/main/oura/BLE.md) describe the
separate timestamped historical record envelope; they do not validate this candidate Live age.


## Target observation after adding delivery metadata

On the user's Gen5/OnePlus 15 with Android 12.5.0 build 568, a retained window contained **21 HR
readings in 21 separate notifications**, with a largest notification group of one. Adjacent callback
gaps ranged from **0 to 7,629 ms**, with the latest gap at **1 ms**. The maximum callback-to-owner
queue wait was **2 ms** (latest 0 ms), and there were no out-of-order receive times. A zero gap here
means the captured millisecond clock values matched, not that measurement instants were identical.

This reproduces closely grouped delivery to Android callbacks with little measured owner-queue
backlog in that window. It does not locate buffering within the sensor, peripheral, radio/controller
or Android before the callback, and it does not date acquisition or measure UI rendering delay.
The popup and safe end-session log reported the same timing summary. No HR values were exported
for this observation.
