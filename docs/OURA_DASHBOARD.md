# Oura dashboard

Open **More → Data → Oura** in the Android app. This dashboard change is Android-only.
Cracked-Oura and open_health are reference material for data presentation and functionality.

Select a paired ring, **Oura export**, or **Oura API history**. Each is a separate local source:
the page never fills an Oura gap with another ring, a WHOOP, or an import. The first visit to a source
opens its latest stored heart-rate day (ring) or daily record (import). Select a date and a 1-, 7-, or
30-day window to browse history. Android's date label opens the date picker.

**Manage ring** opens the existing pairing and device controls. **Import history** opens the existing
local-file import screen. **Refresh** reloads the local database; it does not initiate a Bluetooth sync
or contact Oura. The API-history option only reads existing local records under that source; it does
not add an API connection to Android.

## What the page shows

| Source | Available data |
| --- | --- |
| Ring over Bluetooth | Heart rate, raw oxygen channel, skin temperature, ring-reported HRV summaries, experimental breathing summaries, movement, battery history, reported sleep-stage timeline |
| Local Oura import / API history | Resting HR, HRV, blood oxygen, respiratory rate, temperature deviation, steps, active/total energy, sleep/deep/REM duration, reference readiness/sleep/activity scores, VO₂ max, stored sleep sessions |

Only metrics present in imported history appear. Bluetooth cards describe missing samples rather than
fabricating zeros. Support depends on the ring's generation, firmware, configuration, and the records
it actually sends; this page does not unlock additional ring features.

Oxygen from Bluetooth remains **unvalidated**. The existing 50–110 display gate excludes historical
DC-channel contamination, but values above 100 remain visible to expose unresolved calibration.
This raw channel is separate from imported blood-oxygen percentages and from NOOP's default-off
nightly candidate display. Ring HRV is the stored `OURA_HRV` RMSSD summary, never a reconstruction from
banked intervals. Breathing summaries remain experimental. Neither display enables Recovery scoring.
Movement is seconds per ring window, not steps. Imported Oura scores remain reference values.

## Chart behavior

- The line shows the median in each 5-minute (1 day), 1-hour (7 days), or 6-hour (30 days) bucket.
- **Show ranges** adds the original minimum-to-maximum envelope. Extrema are also available in the
  inspected bucket and footer when the envelope is hidden.
- Tap or drag horizontally to inspect the bucket's time, median,
  extrema, and original reading count. Tapping an empty bucket clears the inspection.
- Empty buckets break the line. Values are not interpolated across them; empty time inside a populated
  bucket is not represented at finer resolution. Use a shorter window for more detail.
- Mean/min/max and reading counts use the accepted original samples, not the reduced medians.
- Daily imports stay daily. Calendar-day windows handle daylight-saving changes, and temperature
  respects the existing Celsius/Fahrenheit preference, including deviations without an absolute offset.
- Raw reads run off the UI thread in bounded batches of at most 24 hours. A storage error or exceeded
  read limit produces a retryable error instead of a silently truncated chart.

The Android Deep Timeline also accepts the ring's single-channel oxygen samples, matching the existing
Apple behavior; it previously dropped every row with an absent infrared channel.

## References and verification status

[Cracked-Oura](https://github.com/EIrno/Cracked-Oura) informed the dedicated local-data overview and
imported metric coverage. [open_health](https://github.com/Th0rgal/open_health) informed the separation
of sleep, activity, and vital signals. No reference-project implementation or assets were copied.

Device verification resumed on 2026-10-14 using a OnePlus 9 Pro (LE2125), Android 16 / API 36,
over wireless ADB. The Full debug app built and installed successfully. Seven instrumentation tests
exercise real Room reads and Compose UI using synthetic records:

- Ring/source isolation, exclusive time boundaries, accepted single-channel oxygen, and temperature scaling.
- Imported metric precedence and a 23-hour daylight-saving calendar day.
- Chart taps and horizontal scrubbing, missing-bucket clearing, and exact-value accessibility actions.
- Source/window/range controls at 200% font scale.
- Dashboard loading, refresh, and saved-state restoration.
- Sleep totals and timeline clipping to the same edited session, plus summary-only imported sleep.

Full and Demo debug APKs build successfully. Each JVM suite reports 6,687 tests, zero failures/errors,
and six existing skips. The diff-scoped localization audit, doc-comment lint, and `git diff --check` pass.
Full and Demo staging-release assembly and vital lint also pass. Combined release lint exhausted the
default 2 GB Gradle heap; the successful local run used
`-PstagingRelease -Dorg.gradle.jvmargs="-Xmx4096m -Dfile.encoding=UTF-8" --max-workers=2`.

The sleep regression first failed on the phone (15 reported minutes for a 10-minute visible interval)
and passed after the fix. JVM coverage also checks medians, original-sample statistics, missing buckets,
nonfinite samples, and batch equivalence. Test screenshots use synthetic data and remain build artifacts.
The instrumentation dependencies use Espresso 3.7.0 and AndroidX JUnit 1.3.0 for Android 16 compatibility;
dependency locks and verified checksums are committed.

The source strings have English resources; new translations remain pending. Existing Bluetooth,
storage schema, import parsing, and scoring behavior are unchanged.

### Reproducing the device checks

From `android/`, with the SDK and JDK configured:

```sh
./gradlew assembleFullDebug testFullDebugUnitTest --no-build-cache --rerun-tasks
./gradlew assembleFullDebugAndroidTest
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/full/debug/app-full-debug.apk
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/androidTest/full/debug/app-full-debug-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class com.noop.ui.OuraDashboardDeviceTest \
  com.noop.whoop.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Keep JVM tests and instrumentation assembly in separate Gradle invocations: test-source KSP can
invalidate the Room schema export in a combined invocation. The direct ADB runner was used because
AGP 8.5.2's connected-device discovery threw a host-side null-pointer exception for this wireless device.
Check the runner's `OK (7 tests)` summary; the ADB process exit status alone does not establish success.
Screenshots are saved in the debug app's external-files `oura-test-evidence` directory.

## Android interaction and motion

- The page uses NOOP’s shared segmented pills, buttons, backdrop preferences, and card entrances.
- Source, date, window, and range visibility survive configuration changes and navigation state restoration.
- Refresh keeps the current window visible; a failed refresh labels the retained readings as previously
  loaded. Changing source or date never displays the previous window under the new controls.
- Rapid date/window taps are coalesced before database work. Metrics and individual sleep sessions use
  lazy list items; visible sleep timelines are parsed off the UI thread.
- Chart geometry is cached. Horizontal inspection uses a binary time-bucket lookup and reserved readout
  space; it does not rebuild paths. Large text can expand rather than truncate the readout. Vertical
  gestures remain scrolling.
- Range bands fade over a fixed chart scale. Band and entrance motion respect Android Reduce Motion.
  No animation interpolates physiological readings. Clock labels follow NOOP’s clock preference.
- TalkBack exposes previous/next reading actions with exact bucket timestamps, extrema, and counts.

The tested large-font controls and chart inspection rendered successfully on the phone. Accessibility
actions were invoked through Compose semantics; spoken TalkBack navigation has not been verified.
Large-history scrolling performance, error/refresh races, live ring collection, and calibration remain
outside this device run. No real biometric data or Bluetooth ring commands were used by the tests.
