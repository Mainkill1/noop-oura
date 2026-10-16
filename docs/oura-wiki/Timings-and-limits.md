# Timings and limits

[Index](Home.md). Values are as of Android 12.4.1 (566). **NOOP budgets are implementation
choices, not measured hardware capacity or guarantees.** Apple lifecycle/transport can differ.

| Area | Value or behavior | Evidence and meaning |
| --- | --- | --- |
| Live page reconciliation | 150 ms settling worker | NOOP; removed demand invalidates pending starts immediately; UI spam cannot accelerate acquisition |
| Local GATT writes | One outstanding operation per connection queue | NOOP; callbacks and ownership govern advancement |
| Local write callback deadline | 5 s | NOOP; ambiguous timeout closes the session without blind replay |
| Disconnect cleanup budget | 1.5 s | NOOP; process kill/radio loss can prevent delivery |
| Authentication nonce watchdog | 10 s per escalation window | NOOP; bounded nonce resend, CCCD toggle, then disconnect; silence is not a key verdict |
| HR renewal | 15 s while HR is selected and foreground | NOOP; paced requests, independent of UI refresh speed |
| HR first pulse | No ten-second permanent cutoff | NOOP correction; initial candidate saw a first pulse at 9 s; regression accepts 45 s |
| HR stream cadence | Approximately 1 Hz in prior research | Existing protocol observation; not a guaranteed new physiological sample every second |
| HR exit | Restore automatic mode 1, then subscription 0 | NOOP corrective lifecycle; do not substitute feature mode 0 |
| HR auto-revert | Not reliable on target Gen5 | Target observation: mode 3 persisted for minutes after unsubscribe alone |
| Motion wire request | One-minute lease | Existing protocol contract; zero-duration unlimited requests are not used |
| Motion renewal | After 50 s from actual submission | NOOP; at most one pending/outstanding renewal, congestion can still leave gaps |
| Motion first sample | 10 s | NOOP motion-only cutoff; silent motion marked unavailable without automatic retry |
| Motion throughput | Approximately 49 vectors/s | Target earlier capture, valid 14-byte bodies; not a combined HR/motion capacity promise |
| Motion session memory | 60 s / 6,000 vectors | NOOP rolling inspection buffer; all accepted vectors also contribute to persistent per-second summaries |
| Raw read polling | 250 ms, 500 ms, 1, 2, 5, 10 or 30 s | NOOP selectable completion-based delay; no catch-up burst or concurrent request accumulation |
| Raw evidence memory | 256 records, 64 KiB bytes, 32,768 numeric values | NOOP bounded volatile diagnostics; not a full packet archive |
| History page request | Maximum 255 events | Current builder/call path; larger logical page formats are not established |
| History quiet boundary | 1.5 s without record arrivals | NOOP continuation budget; summary arrival alone is insufficient |
| History drain elapsed guard | 300 s, evaluated when a summary arrives | NOOP policy; not an unconditional timer for a silent/no-summary drain; keep banked progress when the guard is evaluated |
| History stall bound | Three non-shrinking remaining-byte summaries | NOOP guard; not a universal peripheral response deadline |
| Chained backlog pass delay | 5 s | NOOP; only known remaining work/reboot recovery chains |
| Chained pass cap | Six chained passes | NOOP bound; periodic fetch/reconnect can continue from saved cursor |
| Periodic history check | 300 s | NOOP idle scheduling; current live page can defer it |
| Resume timestamp ceiling | 500,000,000 ring ticks | NOOP corrupt-cursor guard, not storage capacity |
| Official spot HR | Approximately 10 s of stillness; optical PPG acquisition described as 50 Hz | [Official live HR guide](https://support.ouraring.com/hc/en-us/articles/4410651298963-Live-Heart-Rate); optical sampling is not BLE notification or new BPM rate, and this is not NOOP's continuous first-pulse deadline |
| Background HR | One-minute opportunities every five minutes; automatic gaps can reach 30 minutes | [Official HR guide](https://support.ouraring.com/hc/en-us/articles/4410656562579-Heart-Rate-Graph); suitability-dependent product policy, not a continuous notification promise |
| Oxygen | Sleep-associated estimate for eligible periods longer than three hours, choosing the longest qualifying episode | [Official oxygen guide](https://support.ouraring.com/hc/en-us/articles/7328398760851-Blood-Oxygen-Sensing-SpO2); no calibrated continuous live stream established |
| Ring-local retention | Up to 3 days Ring 5; up to 7 days Ring 4/Gen3 | [Public product guidance](https://support.ouraring.com/hc/en-us/articles/360025428394-Product-Safety-Use); not an independently measured research-buffer capacity |
| Official history operation watchdog | 120 s in examined paths | Static candidate; distinct from quiet gaps; not a NOOP policy or validated ring limit |
| Official extended batch/recovery | Initial target 64; one path up to 3 rereads at about 100 ms scheduling delay | Static candidate; not a proven safe batch size or retry recipe; terminal reconstruction incomplete |

Android 12.5.0 adds a 300-second host transport-boost lease, checked against elapsed time at
connection-executor entry as well as by its scheduled timer. It releases priority/PHY preferences;
it does not stop Live or declare an unfinished history drain complete. Standard PHY reading occurs
once per connection; priority requests occur once per actual drain and at its exit. See
[transfer controls and recap](BLE-transfer-speed.md#current-noop-baseline).

Source locations: [Android transport](../../android/app/src/main/java/com/noop/ble/OuraLiveSource.kt),
[queue](../../android/app/src/main/java/com/noop/ble/OuraWriteQueue.kt),
[history decision core](../../android/app/src/main/java/com/noop/oura/OuraHistoryDrain.kt),
[authentication policy](../../android/app/src/main/java/com/noop/oura/OuraAuthWatchdog.kt),
[live contract](../OURA_LIVE_PROTOCOL.md) and [raw contract](../OURA_RAW_INSTRUMENTS.md).

## Timing labels

Queue wait, local write completion, matching response arrival, first actual sample, sample age,
notification inter-arrival and full history completion are different measurements. A successful
write or ACK does not establish sensor readiness. Replies have no general per-request identifier,
so matching a response provides observed timing, not unique correlation proof.

An open Live page continues its requested feed. Leaving it or task-switching stops foreground
demand and paced renewals. Locally accepted cleanup is not proof the ring received it: current
mode read-back and real samples are stronger evidence.
