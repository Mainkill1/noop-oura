# Oura explorer: research, plan review, and independent specification

## Scope and provenance

Android Oura UI in PR #1. Comparative OSS research: lessons from public behavioral documentation
and screenshots, followed by a repo-native implementation. No external implementation, assets,
styles, component structures, models, or microcopy are reused. No claim of separate-person clean-room
certification. Storage/protocol/scoring contracts remain unchanged.

Research snapshots:

- [Cracked-Oura](https://github.com/EIrno/Cracked-Oura/tree/db254b2790bef4035c4f6534ec6bac76e16ed9d1),
  commit db254b2790bef4035c4f6534ec6bac76e16ed9d1. No repository license declared at review time;
  research restricted to its public README. It describes a customizable historical dashboard covering
  sleep, readiness, and activity. It does not document a live mode or four-signal overlay. Those are
  requirements from this user, not attributed reference features.
- [open_health](https://github.com/Th0rgal/open_health/tree/08e1b868e1c5ad9c5c63c2adedaee86c3e1c1bfa),
  commit 08e1b868e1c5ad9c5c63c2adedaee86c3e1c1bfa, MIT. Reviewed public dashboard guide and
  overview/sleep/activity screenshots. Overview gives summaries before details; sleep and activity
  have category navigation; sleep signals align to a common time axis; activity has a movement plot.
  Public screenshots show stacked signals, not a four-signal overlay. The guide also documents
  local credential transfer, separately from health presentation.

## Measured UX gaps

| Current NOOP behavior | Gap | Planned outcome |
| --- | --- | --- |
| One long overview/sleep/graph list | Health and sleep hard to focus | Health, Sleep, Movement, Compare tabs |
| Calendar history only | No intentional live view | Two-position sliding Live/History selector |
| Individual plots use compact 130dp area | Detailed signals too small | 220dp individual plots, 320dp combined plot |
| Seven ring overview signals, motion duration only | Stored axes/intensities are undiscoverable | Movement windows, duration, axes, orientation, intensity |
| Separate plots only | Cannot compare up to four signals by time | Shared-time combined plot with explicit unit scales |

## Independent behavioral specification

1. The Oura primary tab remains the single entry point. Shared sliding segmented control chooses Live
   or History. A distinct category tab row selects Health, Sleep, Movement, or Compare. Mode, category,
   source, date, and comparison selection survive recreation. No category opens a separate app window.
2. History retains source-isolated 1/7/30-day calendar navigation. All visible metrics and charts use
   the same loaded source and time interval. Same-window refresh retains readings; stale/error states
   remain labeled. Overview precedes large graphs within each category.
3. Live is available for the selected active Oura ring only, never another band's cached state or an
   import. It shows a trailing 15-minute interval, refreshes stored readings every five seconds while
   the UI is started, and identifies latest observation timestamps. The current reading comes from an
   actual timestamped sample, never a timer resampling a cached number. A disconnected/nonstreaming
   source is labeled accordingly. Other sensors are described as recently recorded, not guaranteed
   continuous streams. Sleep directs the user to History. Backgrounding stops polling. Reuse existing
   source behavior; do not introduce or automatically send new ring commands.
4. Movement displays existing ring-recorded motion windows: motion seconds, averaged raw X/Y/Z,
   optional low/high intensity, and orientation. Missing optional fields stay missing. These are
   movement-gated summaries, not continuous acceleration, activity classification, calories, or sleep
   staging. Do not derive magnitude, METs, movement coverage, or exercise sessions. Imported steps and
   energy remain separately labeled imported metrics.
5. Compare permits zero through four explicitly selected numerical signals across categories from the
   same source/window. A fifth selection is disabled without removing existing choices. The bottom
   axis is time. Compatible dimensions and units share a domain, e.g. raw X/Y/Z axes. Absolute and
   relative temperature, raw and calibrated oxygen, heart rate, HRV, charge, and sensor intensity stay
   distinct. Temperature and heart rate have different labeled scales even when their numerical
   values happen to overlap. Each scale has visible units and bounds; no unlabeled normalization.
6. Combined traces preserve empty buckets and exact timestamps. A shared cursor returns each selected
   signal's actual bucket value or a missing indication at that time. No snapping across gaps and no
   fabricated interpolation. Different colors plus labels/stroke styles distinguish traces. Touch
   and accessibility actions provide equivalent inspection. Constant-valued signals get a finite
   padded domain. Range visibility does not alter domains.
7. Use Palette, Metrics, NoopType, NoopCard, shared segmented pills, Material category tabs, consistent
   metric labels/units, the clock/unit preferences, large-text wrapping, and Reduce Motion behavior.
   Keep the UI offline. Cross-platform scope remains the Android dashboard requested in this session;
   changes are display/read projections only, with no new persisted physiological values.

## Implementation plan and acceptance

1. Review this plan independently before implementation. Resolve scale, source-isolation, sensor
   provenance, and lifecycle issues first.
2. Test then add pure category/selection/axis-domain/bucket-lookup helpers and latest-original-sample
   statistics. Test incompatible dimensions, shared X/Y/Z domains, four-signal limit, gaps, flat values,
   missing sensor fields, and source/window bounds.
3. Extend the existing read projection with stored motion fields and bounded recent-window details.
   Use in-memory Room device fixtures to verify field values, missing/invalid payloads, and isolation.
4. Implement mode/category controls, lifecycle-bounded live refresh and accurate live status. Preserve
   source/date isolation and historical refresh. Extend production overview components and large plots.
5. Implement the combined plot and field selectors, visible scale legends, shared inspection and
   accessible actions. Verify four selections, fifth disabled, gaps, unit conversion, and restoration.
6. Run JVM suites and Android builds, then device instrumentation and visual inspection on the OnePlus.
   Exercise History/Live, Health/Sleep/Movement/Compare, multi-signal selection, large text, missing data,
   and timestamps. Distinguish simulated/in-memory fixtures from live ring validation.
7. Update documentation and PR #1. Publish a new prerelease NOOP APK after verification; retain the
   existing key-exporter artifact without silently replacing the prior release's binaries.

## Plan-review record

Independent review completed before production implementation. The reviewer requested precise freshness,
categorical orientation, bucket provenance/alignment, measurable bounds, and timestamp-less battery handling.
Resolved requirements:

- A live HR badge requires the selected active Oura identity, connected + streaming flags, and an
  original stored HR sample no more than 30 seconds old and not in the future. Otherwise show waiting,
  disconnected, or last-recorded status. Connected/authenticated alone never means live.
- Other sensor values are explicitly latest recorded observations within the trailing 15 minutes;
  they are not promised live. Each has its own recorded timestamp. Values outside that interval or in
  the future are missing. Sleep remains historical.
- Live windows start on a five-second epoch lattice; five-second buckets retain alignment on refresh.
  Live tiles show latest original sample values. Chart traces and inspection show bucket medians plus
  the bucket interval, extrema and sample count, without implying a median occurred at its midpoint.
- Orientation is shown only as a latest categorical code (0..7), never averaged or offered in Compare.
  X/Y/Z are uncalibrated averaged raw values already multiplied by eight by the existing decoder;
  they are not labeled g or continuous raw samples.
- Timestamp-less battery StateFlow is not resampled or put on charts. Only timestamped stored battery
  rows participate in overview/comparison.
- Reads retain the existing rejection at 200,001 rows per daily query and at 2,001 sleep sessions.
  Recent motion detail is bounded to 24 windows. Live plots have at most 181 five-second buckets per
  signal. One lifecycle-started polling job runs sequential loads, waits five seconds after completion,
  cancels on stop/source/mode change, refreshes immediately on resume, and checks generation before
  publishing. A slow poll cannot overlap another poll. Invalid individual event payloads are skipped;
  storage/query failures remain errors rather than successful empty data.
- Tests cover stale/future HR, disconnect, source replacement, mixed sample rates, gaps, malformed
  motion events, optional fields, and stable buckets as well as four-trace/axis behavior.

## Implementation review and evidence

The final source review found no blocking correctness gaps after correcting clock preferences,
rejecting malformed motion axes/intensities, preventing overflowed comparison domains, and expiring
stale Live tiles. The production refresh helper is exercised with a stopped/resumed lifecycle, a
12-second read with no overlapping polls, and an obsolete source whose read ignores cancellation.

Full and Demo debug assembly pass. The Full JVM suite reports 6,706 tests, no failures/errors, and
six existing skips. Localization, detached-doc-comment, and diff checks pass. A OnePlus 9 Pro running
Android 16/API 36 passes 18 direct instrumentation checks across the explorer, dashboard, and key
transfer. These include source-isolated Room reads, optional and malformed motion fields, sliding
mode/category controls, four-signal selection and restoration, gap-preserving shared inspection,
accessibility actions, large text, navigation, sleep clipping, and existing XML/credential behavior.
Populated comparison and movement screens were visually inspected from Compose screenshots.

The fixtures are synthetic and in memory. Live ring collection, spoken TalkBack, calibration, and
large-history performance have not been validated by these checks. The root manager’s standalone
exporter grant remains unverified; this UI change neither changes root behavior nor adds ring commands.
