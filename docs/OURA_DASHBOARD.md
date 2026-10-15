# Oura dashboard

Tap **Oura** in Android’s bottom navigation. One dashboard offers **Health**, **Sleep**,
**Movement**, and **Compare** through a category dropdown, with a separate sliding **History / Live** selector.
The compact instrument layout uses NOOP’s typography and color tokens with flat panels, thin
borders, small corner radii, and dense controls. Graphs receive more of the available screen space.
The dashboard and live-session lifecycle are Android-specific. Pure live-motion wire decoding and
command builders have Kotlin/Swift twins. Motion remains raw instrumentation and does not change scoring.

## Sources and time

Choose a paired Oura ring, **Oura export**, or **Oura API history**. Each is an isolated local source;
missing readings are never filled from another ring, a WHOOP, or an import. Opening the app connects
the active ring and downloads its recorded history incrementally. Already stored records remain
available while that download runs. Entering a Live page requests only the streams that page needs; leaving it stops those streams.

**History** offers a calendar date and 1/7/30-day window for the dashboard. Category overviews precede
the larger detailed graphs. Missing values remain dashes. Ring-recorded measurement time remains
separate from download time: records whose time cannot be anchored must not receive a current-time
stamp or make a live indicator appear fresh. History refresh reloads local records; connection-owned
synchronization is separate from that UI refresh. Imported sources never initiate ring measurement.

## Page-owned Live sessions

Live uses direct notifications from the selected active ring, independently of database reloads.
The visible foreground page owns one session. Changing category or selected signals updates its
desired workload through a central helper. The helper coalesces changes, removes obsolete queued live
commands, preserves accepted in-flight work, and orders shutdown before enabling the final selection.
Repeated navigation cannot create extra workers or grow an obsolete command backlog.
New stream starts settle for 150 ms through one pending reconciliation. Removed streams are cancelled
immediately; streams retained by the new selection continue without a restart.
Source/session guards reject stale UI requests, including requests against a different ring or an
earlier connection to the same ring. Leaving Live, task switching, or changing source ends the session.
Returning to a visible Live page requests its current selection again. Stream failures do not trigger
an unbounded retry loop.

| View | Live workload | Display |
| --- | --- | --- |
| Health | Heart rate | Latest actual HR and Raw/Smoothed trend |
| Movement | One accelerometer stream | X, Y, Z together on a common raw-count scale |
| Compare | Selected HR and/or accelerometer streams | Up to four traces; HR plus XYZ uses two streams |
| Sleep | None | Direct action to recorded sleep in History |

Live continues while the page stays visible in the foreground. The helper renews the ring's finite
motion request without restarting the displayed session. Navigation and task switching cancel renewal
and send shutdown; no user-facing timer ends an otherwise active page. The finite request bounds
residual motion if the process dies or a shutdown cannot reach the ring.

Motion is raw instrumentation with no validated conversion to g and does not feed scoring, steps,
calories, or sleep staging. Live XYZ is reduced into persistent historical summaries retaining sample
counts, means, and extrema. Partial summaries are flushed when collection stops. Host receipt-time
provenance remains separate from the ring's recorded measurement time. These summaries are distinct
from older ring-banked movement records; the two raw formats are not silently treated as equivalent.
Existing HR persistence continues separately. Display smoothing never rewrites stored values.
The first byte of a motion packet is opaque metadata; displayed receive rates are measured from
arriving samples, not inferred from that byte.

Each stream has its own status and receipt age: waiting, receiving, no new samples, stopped, or
unavailable. The display uses relative labels such as **Updated 3 seconds ago**; the age continues
advancing when samples stop. Bluetooth connection and history download status appear separately.
An accepted control write alone does not establish a live stream. History yields at a completed page
boundary while live work is active and resumes from its saved progress after shutdown.

## Categories and historical movement

| Category | Ring records | Imported daily history |
| --- | --- | --- |
| Health | Heart rate, raw oxygen channel, skin temperature, HRV summaries, experimental breathing, timestamped battery | Resting HR, HRV, blood oxygen, breathing rate, temperature deviation, reference readiness, VO2 max |
| Sleep | Stored sleep sessions and stage timelines | Sleep/deep/REM durations, reference sleep score, stored sessions |
| Movement | Captured live XYZ summaries; ring-banked motion seconds, averaged raw axes, low/high intensity, orientation inspector | Steps, active/total energy, reference activity score |
| Compare | Up to four numerical signals across categories | Up to four numerical signals across categories |

Captured live movement stores one-second raw signed16 summaries. Its graph means and peak bounds
retain actual sample counts and host receipt-time provenance. The latest 120 summaries are available
for inspection; this inspection limit does not delete earlier captured seconds from History.

Ring-banked movement windows are movement-gated summaries, distinct from captured live accelerometer
samples. The inspector browses the latest 24 recorded windows. Their signed-byte axis fields are
multiplied by eight by the existing decoder, with no validated conversion to g. Intensity remains
unscaled. Orientation is categorical (0 through 7), never averaged or offered as a numerical graph.
Missing optional fields remain missing. Imported steps and energy retain their own units.

Ring oxygen remains **unvalidated**. The existing 50–110 display gate excludes historical DC-channel
contamination; values above 100 remain visible to expose unresolved calibration. This raw channel is
separate from imported blood-oxygen percentages and NOOP's default-off nightly candidate. Ring HRV
is the stored OURA_HRV RMSSD summary. Breathing remains experimental. These displays do not enable
Recovery scoring. Imported Oura scores remain reference values. Availability depends on the ring's
firmware, configuration, and actual records.

## Graphs and comparison

Live offers 10/30/60-second scrolling windows. HR smoothing uses a three-second trailing mean
for the displayed trend and resets across gaps; XYZ smoothing uses a separate 250-millisecond trailing mean.
The latest numbers and raw inspection remain unchanged. Motion reduction preserves extrema so brief movement
spikes remain visible. Missing data creates gaps. X/Y/Z share a scale; HR and raw acceleration retain
separate labeled scales. Plot colors, labels, and stroke patterns distinguish traces.

Historical ring plots use median buckets: five minutes for one day, one hour for seven days, and
six hours for 30 days. Imports remain daily, including daylight-saving calendar boundaries.
**Show ranges** adds min/max bands on individual history plots. Original-sample means, extrema,
and counts remain independent of bucket medians and range visibility.

Compare accepts zero to four signals. Compatible quantities and units share a domain. Absolute and
relative temperature, raw oxygen, calibrated blood-oxygen percentages, HR, charge, and intensity
remain distinct. Celsius/Fahrenheit and 12/24-hour history labels follow app preferences. Touch and
accessibility inspection preserve gaps and original/bucket provenance; no cached value is resampled
to fabricate measurements. Historical bucket medians are not claimed to be original samples.

Settings is directly accessible from the dashboard. Ring credential transfer remains available
under **Transfer ring keys**. See [Oura key transfer](OURA_KEY_TRANSFER.md).

## Continuous Live and compact layout verification (12.3.0)

The Android Full JVM suite passes with 6,792 tests, zero failures/errors, and six existing skips.
Full and Demo APKs and the Android instrumentation APK compiled. The final source-incarnation fix
then passed 62 focused tests and rebuilt Full and instrumentation APKs. Focused tests cover automatic page
entry, pending and active rapid reentry, pause/disposal cancellation, selected streams, display-only
smoothing, gaps, raw inspection, and stored motion envelopes. Kotlin and Swift strict-summary tests
also pass after the final constant representation cleanup.

Full package runs passed 273 OuraProtocol tests and 622 WhoopStore tests. Subsequent focused
summary and persistence checks cover malformed JSON, integer boundaries, recoverable merge rejection,
transaction rollback, concurrent partial seconds, source isolation, and preservation of older movement.
Native Swift oracle output is pinned in both platforms, including every signed16 input and malformed
payload boundaries. All 343 Python Tools tests pass with the native Swift CLI available; five execute
its actual capture replay, command inspection, and summary export. Localization and doc-comment
checks pass. The final parity acceptance rerun passes all ten checks.

Derived parity snapshots were refreshed against a metadata-only repair of the exact starting source
commit. The starting snapshots were already stale; the documented `--repair-stale-base` procedure ran
in an isolated checkout before validating these changes. No authority migration or shared-parity waiver
was used. This local result does not imply that the fork's older remote baseline or an empty GitHub
Actions roster passed. Apple app targets and StrandTests were not run in this Linux environment.

Version 12.3.0-debug (562) was installed on the OnePlus 15. A phone-only encrypted-preferences comparison
immediately after installation confirmed unchanged credentials. Final device UI and continuous-stream
acceptance remain pending; the first instrumentation attempt was interrupted because the phone was
dozing with its lock/notification shade above the test activity. This is not a passing device test run.

## Earlier dashboard verification

Before implementation, public guides and screenshots from
[Cracked-Oura](https://github.com/EIrno/Cracked-Oura) and
[open_health](https://github.com/Th0rgal/open_health) were reviewed for overview, category navigation,
and time-aligned signal presentation. The four-signal overlay and Live mode are requirements for NOOP,
not attributed features of those references. An independent plan review resolved freshness, units,
categorical orientation, bucket provenance, bounds, and lifecycle behavior. No external implementation,
assets, styles, or copy were reused. Source snapshots and the independent specification are recorded in
[the explorer design note](superpowers/specs/2026-10-14-oura-explorer.md).

Full and Demo debug builds and the Full JVM suite pass: 6,706 tests, zero failures/errors, six existing
skips. The suite includes selection limits, compatible/incompatible scales, Fahrenheit conversion,
finite domains, exact gap lookup, original latest values, freshness, bucket alignment, stop/resume,
serial slow polling, obsolete-source cancellation, and clock preferences. Localization checks cover
English, German, Spanish, French, and European Portuguese; doc-comment and diff checks pass.

Device verification uses a OnePlus 9 Pro (LE2125), Android 16/API 36, over wireless ADB.
All 18 explorer, dashboard, and key-transfer instrumentation checks pass. They cover the sliding mode
selector, category tabs, four-signal limit, saved selections, shared gap inspection, movement parsing,
source isolation, large text, navigation, sleep clipping, and existing XML/credential behavior.
Populated comparison and movement screenshots were visually inspected. Tests use synthetic in-memory
records and production Compose components; they do not validate live
ring collection, calibration, or spoken TalkBack. The standalone rooted exporter was subsequently
verified on a OnePlus 15; see
[the key-transfer verification](OURA_KEY_TRANSFER.md#verification).

### Android Bluetooth delivery fix (12.1.1)

Android accepts only one outstanding characteristic write per GATT connection. Previously, startup
and post-authentication commands were submitted in bursts without waiting for write completion.
Heart rate could stream while history and status requests were rejected locally, leaving the other
Live categories empty. The Android transport now queues the existing commands in order and advances
from `onCharacteristicWrite`. A rejection, failed completion, or five-second timeout closes the session
without replaying an uncertain command. Stop retains only the accepted write and the existing HR
shutdown pair, with a bounded teardown deadline. Callbacks and lifecycle changes run on the main looper;
stale connections cannot complete a new connection's queue. No protocol payloads, decoders, stored
values, or Swift transport behavior change.

The Full JVM suite passes with 6,716 tests, zero failures/errors, and six existing skips. Ten queue
regressions cover startup bursts, notification/write ordering, rejection, failed completion, timeout
without key-command replay, stale timers, close, and teardown. Full debug APK assembly passes.
On a OnePlus 15 running Android 16, build 12.1.1-debug (557) authenticates with the preserved imported
key, receives fresh heart-rate readings, and drains stored history through successive cursors. The
previously empty RR-interval, temperature, and movement tables receive real ring records. The Oura
Live Health view shows a fresh stream with a current observation time, and History → Movement
shows populated duration, X/Y/Z, and intensity summaries over a seven-day window. This verifies delivery and
collection on that device; it does not establish live streaming for every sensor, oxygen calibration,
or availability of sensor features the ring reports inactive. Older recovered records appear in
History, not in the Live view's last-15-minute window.

### Android history progress and opt-in Live (12.1.4)

A long initial download could repeatedly restart at cursor zero after the five-minute drain limit.
Android stored anchored temperature, oxygen, HRV, breathing, and movement records without marking
their ring times as stored progress; only reconstructed hypnograms updated that counter. The Android
source now mirrors the existing Swift bookkeeping for those history-only records, including records
parked until an anchor arrives. Live IBI and unanchored fallback timestamps cannot advance the cursor.
The source buffer is handed to the existing persistence pipeline before cursor commits, and batches
containing only respiration are included in that handoff. Repository inserts remain asynchronous;
this ordering does not add a database-commit acknowledgement or promise crash-safe cursor durability.
No new BLE commands, decoder formulas, or schema changes are introduced.

Live Movement now says **Live movement unavailable**, explains the implementation limit, and provides
a History action. A fresh HR stream no longer serves as the Movement status heading. This wording is
translated in all five dashboard locales. Menu demand is Android-specific lifecycle wiring; the
existing Apple screen/night stand-down policy is unchanged. Repeated view updates do not replay HR
commands, demand changes during authentication wait for its completion, and live HR refresh can run
alongside an authenticated history fetch. Teardown retains the shutdown pair when an enable may still
be active even if a disable is only queued.

Eight Robolectric regressions exercise the real Android source and persistence sink without opening
Bluetooth: anchored movement/temperature progress, parked records, live IBI exclusion, unanchored
fallback, respiration-only persistence, and buffer handoff before the drain-end cursor changes.
Five original bookkeeping regressions and both review regressions failed before their corresponding
fixes. Swift already contains the history-only bookkeeping; its protocol and stored signal values
remain unchanged.

Public interoperability notes were checked under the clean-room research workflow to distinguish
stored motion from a separate raw stream. [open_oura Ring 5 observations](https://github.com/Th0rgal/open_oura/blob/main/docs/ring-5-observations.md)
report a live accelerometer path, while the [Ring 3 notes](https://github.com/Th0rgal/open_oura/blob/main/docs/horizon-ring3-protocol-cheatsheet.md)
record that some accepted realtime requests produced no samples. [ringverse BLE documentation](https://github.com/ringverse/protocol/blob/main/oura/BLE.md)
identifies the separate realtime control but does not fully specify it. These are public protocol
facts and behavior reports, not copied implementations. No third-party source implementation was
inspected for this patch. Absence of NOOP live-motion support is not a claim that the hardware cannot
stream motion. A future implementation needs its own documented payload, explicit start/stop,
bounded session, decoded raw samples, and hardware validation; this patch sends no realtime-motion
command.

### Reproducing checks

From `android/`, with the SDK and JDK configured:

```sh
./gradlew assembleFullDebug assembleDemoDebug testFullDebugUnitTest --max-workers=2
./gradlew assembleFullDebugAndroidTest --max-workers=2
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/full/debug/app-full-debug.apk
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/androidTest/full/debug/app-full-debug-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class com.noop.ui.OuraExplorerDeviceTest,com.noop.ui.OuraDashboardDeviceTest,com.noop.ui.OuraKeyTransferDeviceTest \
  com.noop.whoop.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Keep JVM tests and instrumentation assembly in separate invocations: test-source KSP can invalidate
Room’s schema export in a combined task graph. Regenerate with `--no-build-cache --rerun-tasks` if the
schema-oracle directory is missing. Direct ADB instrumentation avoids AGP 8.5.2’s host-side wireless
discovery null-pointer error. Require the runner’s `OK` summary; shell exit status alone is insufficient.
Screenshots use Compose capture and remain build artifacts under the app’s external-files
`oura-test-evidence` directory. No real biometric data is inserted by these tests.
