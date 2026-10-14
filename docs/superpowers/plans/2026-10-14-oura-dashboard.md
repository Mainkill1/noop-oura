# Oura dashboard implementation plan

**Goal:** Make locally stored Oura data discoverable on a dedicated page with readable, decimated charts on Android, iOS, and macOS.

**Architecture:** Read one explicitly selected ring through the existing repositories. Reduce samples in bounded daily batches into median time buckets, retaining extrema and sample-weighted statistics. Keep existing Bluetooth collection, database schema, and scoring unchanged.

**Design brief:** The requested references motivate a dedicated overview, historical navigation, sleep and vital charts. Use existing design tokens and navigation. Missing data stays missing. Oxygen calibration and ring-reported HRV/breathing remain explicitly qualified. No new account or network dependency.

**Execution:** Inline implementation, followed by independent source review. Tests, builds, and device exercises are deferred at the user's explicit request.

## Tasks

- [x] Add matching pure signal-reduction helpers in StrandAnalytics and Android analytics: finite values only, half-open ranges, time-bucket medians, extrema, original-sample counts and means.
- [x] Add source-isolated Oura repository loaders: heart rate, oxygen, temperature, ring HRV, breathing, movement, battery, and sleep. Read at most one day per batch and reject truncated reads.
- [x] Add Oura dashboard and chart components on Apple and Android: ring selector, day navigation, 1/7/30-day windows, median/range controls, inspected bucket values, honest empty/error states, sleep and management links.
- [x] Correct Android Deep Timeline's single-channel oxygen handling to match Apple.
- [x] Register navigation, document capabilities and limitations, review the diff, and prepare an informational commit/PR.

## Review focus

- Switching rings must not blend sources or leave stale charts visible.
- Missing samples must not become zero or be connected across empty time buckets.
- Day navigation must use calendar boundaries, including daylight-saving changes.
- Summaries must use original samples rather than equally weighting decimated points.
- Cancellation, storage errors, and read limits must not masquerade as a successful empty read.

## Progress

Repository and reference inspection complete. Android oxygen rejection traced to `FullDayChartScreen.readTimeline`. Existing Swift display gate accepts single-channel values in 50...110 without clamping; Android will use the same established bounds. No device protocol changes planned.

Implementation is staged. Android is the primary platform per the owner’s clarification. Dedicated source loaders include daily-table fallbacks for imported vitals. Independent source review is in progress; no tests, builds, or hardware checks have run.

Independent source review found one important issue: Apple awaited reads could be tagged with a newer live selection before cancellation. The fix captures the request key, source, bounds, and bucket width, and guards every post-await state update against that identity. Reviewed by inspection; no execution validation claimed.

Ruling: Tests, builds, oracle runs, and hardware checks remain deferred under the owner’s instruction. No Bluetooth protocol changes or score derivations were introduced. Raw oxygen uses the existing uncalibrated display gate; imported values remain a separate source.

Deferred minor: Sleep sessions are grouped in one eager card on both platforms, and Android also eagerly composes its signal cards. Per-session lazy composition may improve long-window scrolling; no performance measurement was run. New translations remain pending.
