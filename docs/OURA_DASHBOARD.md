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

This change is source-reviewed only. Tests, builds, and hardware exercises were explicitly deferred
by the fork owner. Runtime behavior has not been verified.
The source strings have English resources; new translations remain pending. Existing Bluetooth,
storage schema, import parsing, and scoring behavior are unchanged.

Long-window rendering has not been profiled. Sleep sessions currently share one eager card, and signal
cards also compose eagerly; per-session lazy rendering is a follow-up optimization.
