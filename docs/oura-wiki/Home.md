# Oura ring communication wiki

Updated 2026-10-15. This reference covers NOOP's known Oura communication surface and
behavioral findings from the installed official Android app, version 7.27.0 (261007085).
It is an inventory of current knowledge, not a complete firmware specification or a claim
that every described feature is supported on every ring.

| Page | Contents |
| --- | --- |
| [Connections and commands](Connections-and-commands.md) | GATT channels, authentication, operation families and expected responses |
| [Historical data extraction](Historical-data-extraction.md) | Backlog retrieval, clocks, pagination, persistence, missing data and coexistence |
| [Official-app protocol candidates](Official-app-protocol-candidates.md) | Newly identified bundling, extended history, connection policies and unvalidated limits |
| [BLE transfer speed](BLE-transfer-speed.md) | MTU, PHY, connection parameters, notification packing and useful measurements |
| [Timings and limits](Timings-and-limits.md) | Current NOOP budgets, observed cadences and their evidence |
| [Evidence and open questions](Evidence-and-open-questions.md) | Provenance, uncertain claims and next research targets |

## Reading evidence correctly

- **Target observation:** measured on the user's Gen5 and rooted OnePlus 15; does not establish
  behavior on other firmware or phones.
- **Earlier capture:** a dated measurement already recorded in this repository, often Gen3/iOS.
- **NOOP behavior:** what the present implementation requests, waits for or stores; a software
  budget is not a hardware limit.
- **Public contract:** a platform API or published protocol fact, with its source linked.
- **Static candidate:** suggested by private APK inspection; not yet validated on a ring.
- **Unknown:** evidence does not settle the result. A successful write alone is insufficient.

The reference keeps radio connection, app authentication, notification subscription, sensor mode,
actual samples, banked history, local persistence and derived analysis separate. A screen can be
connected while no new measurement is available. Faster transfer cannot reconstruct records the
ring never collected.

The detailed byte/layout reference remains [OURA_PROTOCOL.md](../OURA_PROTOCOL.md).
Current foreground ownership and storage rules are in [OURA_LIVE_PROTOCOL.md](../OURA_LIVE_PROTOCOL.md),
and the [raw instrument guide](../OURA_RAW_INSTRUMENTS.md) explains what the UI can inspect.
