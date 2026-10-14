# Oura dashboard

Tap **Oura** in Android’s bottom navigation. One dashboard contains **Health**, **Sleep**,
**Movement**, and **Compare** tabs, with a sliding **Live / History** selector above them.
The layout uses NOOP’s shared cards, segmented pills, typography, colors, and spacing.
This dashboard update is Android-only; storage, scoring, and ring commands are unchanged.

## Sources and time

Choose a paired Oura ring, **Oura export**, or **Oura API history**. Each is an isolated local source;
missing readings are never filled from another ring, a WHOOP, or an import. **Manage ring** opens the
existing device controls. **Import history** opens local-file import. **Refresh** reads the local
database; it does not initiate Bluetooth synchronization or contact Oura. API history reads records
already stored under that source and does not add an API connection.

**History** offers one calendar date and 1/7/30-day window for the entire dashboard. The first visit
to a source opens its latest stored heart-rate day or imported daily record. Tap the date to use the
calendar. Category overviews show original-sample averages before detailed graphs. Missing values
remain dashes. Graphs appear only for signals with observations. At large font sizes the overview
uses one column. Individual plots are 220dp high; the combined plot is 320dp.

**Live** requires the selected ring to be the active Oura device. It shows the last 15 minutes of
stored observations in five-second buckets. Reads run sequentially while the screen is started,
with a five-second delay after each completed read; backgrounding stops polling and resuming refreshes
immediately. Tiles show each sensor’s latest original observation and timestamp, and expire readings
older than 15 minutes. They never resample a cached value to create new observations.

A fresh-heart-rate status additionally requires connected and streaming flags plus an original HR
sample at most 30 seconds old and not in the future. Other sensors can update less often and are
labeled as recorded observations. Timestamp-less battery state is not graphed. Sleep sessions are
historical; the Live Sleep tab provides a History action. Live does not send new ring commands or
promise unsupported real-time sensors.

## Categories and movement

| Category | Ring records | Imported daily history |
| --- | --- | --- |
| Health | Heart rate, raw oxygen channel, skin temperature, HRV summaries, experimental breathing, timestamped battery | Resting HR, HRV, blood oxygen, breathing rate, temperature deviation, reference readiness, VO₂ max |
| Sleep | Stored sleep sessions and stage timelines | Sleep/deep/REM durations, reference sleep score, stored sessions |
| Movement | Motion seconds, averaged raw X/Y/Z, low/high intensity, orientation inspector | Steps, active/total energy, reference activity score |
| Compare | Up to four numerical signals across categories | Up to four numerical signals across categories |

Movement windows are existing movement-gated summaries, not continuous accelerometer samples.
The inspector browses the latest 24 recorded windows. X/Y/Z are signed-byte values multiplied by eight
by the existing decoder, with no validated conversion to g. Intensity remains unscaled. Orientation
is a categorical code from 0 through 7 and is never averaged or offered as a numerical graph.
Missing optional fields remain missing. No movement magnitude, METs, calories, activity classification,
or sleep staging is derived from these fields. Imported steps and energy retain their own units.

Ring oxygen remains **unvalidated**. The existing 50–110 display gate excludes historical DC-channel
contamination; values above 100 remain visible to expose unresolved calibration. This raw channel is
separate from imported blood-oxygen percentages and NOOP’s default-off nightly candidate. Ring HRV
is the stored `OURA_HRV` RMSSD summary. Breathing remains experimental. These displays do not enable
Recovery scoring. Imported Oura scores remain reference values. Availability depends on the ring’s
firmware, configuration, and actual records.

## Graphs and comparison

- Historical ring plots use median buckets: five minutes for one day, one hour for seven days, and
  six hours for 30 days. Imports remain daily, including daylight-saving calendar boundaries.
- **Show ranges** adds min/max bands on individual plots. Original-sample means, extrema, and counts
  remain independent of bucket medians and range visibility.
- **Compare** accepts zero to four signals. A fifth choice is disabled until one is removed; existing
  choices are retained. All traces use the same source and bottom time axis.
- Compatible quantities and units share a domain, such as raw X/Y/Z. Heart rate and temperature use
  separate, explicitly labeled scales with visible bounds. Absolute and relative temperature, raw
  oxygen, blood-oxygen percentages, charge, and intensity remain distinct. There is no unlabeled
  normalization. Celsius/Fahrenheit and 12/24-hour labels follow app preferences.
- Colors, labels, and distinct stroke patterns identify combined traces. Tap or drag horizontally to
  inspect all selected signals at the same time. Each readout shows its actual containing bucket’s
  median, extrema, reading count, and time interval, or a dash when that time has no bucket.
- Empty buckets break lines. Inspection never snaps across gaps or interpolates missing values.
  A bucket median is not claimed to be an original sample at the plotted midpoint.
- Accessibility actions provide previous/next reading inspection. Large text wraps details; vertical
  gestures continue scrolling. Individual chart range and entrance motion respect Reduce Motion.
- Reads run off the UI thread with bounded batches. A failed refresh labels retained readings;
  changing source or interval immediately hides the previous window. Cancellation and generation
  checks prevent obsolete reads from publishing. Malformed movement fields are rejected individually.

Ring credential transfer remains available under the toolbar’s **More → Transfer ring keys**.
See [Oura key transfer](OURA_KEY_TRANSFER.md).

## Plan review and verification

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
