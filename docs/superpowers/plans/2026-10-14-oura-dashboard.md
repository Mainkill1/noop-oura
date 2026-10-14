# Oura dashboard implementation plan

**Goal:** Make locally stored Oura data discoverable on a dedicated page with readable, decimated charts in the Android app.

**Architecture:** Read one explicitly selected ring through the existing repositories. Reduce samples in bounded daily batches into median time buckets, retaining extrema and sample-weighted statistics. Keep existing Bluetooth collection, database schema, and scoring unchanged.

**Design brief:** The requested references motivate a dedicated overview, historical navigation, sleep and vital charts. Use existing design tokens and navigation. Missing data stays missing. Oxygen calibration and ring-reported HRV/breathing remain explicitly qualified. No new account or network dependency.

**Execution:** Inline implementation, followed by independent source review. Tests, builds, and device exercises are deferred at the user's explicit request.

## Tasks

- [x] Add a pure signal-reduction helper in Android analytics: finite values only, half-open ranges, time-bucket medians, extrema, original-sample counts and means.
- [x] Add source-isolated Oura repository loaders: heart rate, oxygen, temperature, ring HRV, breathing, movement, battery, and sleep. Read at most one day per batch and reject truncated reads.
- [x] Add Android Oura dashboard and chart components: ring selector, day navigation, 1/7/30-day windows, median/range controls, inspected bucket values, honest empty/error states, sleep and management links.
- [x] Correct Android Deep Timeline's single-channel oxygen handling to match Apple.
- [x] Register navigation, document capabilities and limitations, review the diff, and prepare an informational commit/PR.

## Review focus

- Switching rings must not blend sources or leave stale charts visible.
- Missing samples must not become zero or be connected across empty time buckets.
- Day navigation must use calendar boundaries, including daylight-saving changes.
- Summaries must use original samples rather than equally weighting decimated points.
- Cancellation, storage errors, and read limits must not masquerade as a successful empty read.

## Progress

Android implementation and independent source review are complete. The dashboard keeps each source
separate and includes daily-table fallbacks for imported vitals. Android oxygen rejection was traced
to `FullDayChartScreen.readTimeline`; its display gate now uses the established 50–110 bounds without
clamping. No device protocol changes were introduced.

Scope: Android only. Cracked-Oura and open_health are reference material. Apple app and Swift package
changes are excluded from the final diff, per the owner's clarification. The new reduction helper is
Android presentation logic and does not change stored data or scoring.

Tests, builds, and hardware checks remain deferred under the owner's instruction. Source review and
`git diff --check` do not establish runtime correctness.

## Android usability refinement

The owner requested interaction and animation consistent with the rest of NOOP. Use shared controls,
backdrop settings, and Reduce Motion-aware entrances; retain same-window data during refresh while
keeping source/window changes isolated. Save navigation controls, render metrics/nights lazily, parse
visible sleep timelines in the background, cache chart paths, and reserve inspection space. Fade only
the optional range bands on a fixed domain; physiological values remain exact. Add accessible bucket
navigation and honor the existing clock preference. Review source and update the informational draft
PR without running tests, builds, or hardware checks.

Implementation and independent source review are complete. Review follow-ups expose selected bucket
values to TalkBack, let large-text inspection details wrap, and key pointer handlers to refreshed
selection state. No performance measurements have been run. New translations remain pending.
