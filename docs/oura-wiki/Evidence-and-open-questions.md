# Evidence and open questions

[Index](Home.md). This page defines the scope of “known,” records uncertainty and prevents a
plausible static interpretation from becoming an automatic ring command.

## Reference set

| Source | Status and use |
| --- | --- |
| NOOP repository on `feat/oura-dashboard`, Android 12.4.1 | Current command, queue, drain, decoder and storage behavior; pure tests do not validate radio behavior |
| Target Gen5 / rooted OnePlus 15, 2026-10-15 | Key-preserving update, authentication/control evidence and HR investigation; only counts, ages and control labels leave phone |
| Earlier dated captures in [OURA_PROTOCOL.md](../OURA_PROTOCOL.md) | Generation/platform-specific observations; preserve their dates and scope |
| Installed official app 7.27.0 (261007085) | Private authorized static inspection by a separate investigator; candidate behaviors only |
| [Official-app candidate inventory](Official-app-protocol-candidates.md) | Essential bundling/extended-history/policy facts with version/hash, incomplete reconstruction and no ring validation |
| [Android GATT API](https://developer.android.com/reference/android/bluetooth/BluetoothGatt) | Primary public contract for MTU/PHY/connection-priority requests |
| [Bluetooth SIG LE primer](https://www.bluetooth.com/bluetooth-le-primer/) | Primary public radio/PHY background; not evidence of ring support |
| [Official live HR help](https://support.ouraring.com/hc/en-us/articles/4410651298963-Live-Heart-Rate) | Spot-measurement conditions, distinct from NOOP continuous instrumentation |
| [Official HR graph help](https://support.ouraring.com/hc/en-us/articles/4410656562579-Heart-Rate-Graph) | Background/spot/activity acquisition contexts |
| [Official oxygen help](https://support.ouraring.com/hc/en-us/articles/7328398760851-Blood-Oxygen-Sensing-SpO2) | Sleep-associated oxygen behavior; not a live-channel specification |

Existing protocol facts retain the citations and licensing notes in the original protocol
specification. The wiki uses those target-owned contracts; it does not copy third-party
implementation expression. Private APKs, reconstructed code, vendor assets, models and firmware
are excluded from the repository and releases. No personal history, key material or account
data is part of this reference.

The main APK inspection loaded 112,782 classes and emitted 41,861 Java files with 335 reported
reconstruction errors. Native code, firmware and model payloads were not decoded or executed.
This is not exhaustive successful decompilation or observation of the official app running.

## Research priorities

| Priority | Question | Evidence required before changing NOOP |
| --- | --- | --- |
| 1 | Does exit restore normal automatic HR and can Live restart? | Target mode read-back, actual late samples, persistent-row growth and lifecycle re-entry |
| 2 | What enables packed history notifications on this Gen5? | Current MTU/PHY, payload-size histogram and records/notification; isolate standard transport from proprietary mode candidates |
| 3 | Do standard high-priority or 2M preferences improve useful history goodput? | Resulting negotiated parameters, comparable backlog and unique persisted throughput; fallback and energy cost |
| 4 | What exactly do flush, reads and acknowledgments do to multi-client retention? | Non-destructive preservation evidence separating seek position, acknowledgment, retention and deletion |
| 5 | Which record families actually arrive on this firmware? | Per-tag counts/lengths/trust status; catalog presence and a feature-mode ACK are insufficient |
| 6 | What are the additional Gen5 characteristic roles? | Passive/authorized evidence and independent framing validation; leave unused meanwhile |
| 7 | How much backlog can be retained, and at what energy cost? | Generation/firmware-specific capacity and transfer measurement; no assumption from another ring generation |
| 8 | How should unknown-only tails and silent/no-summary drains terminate? | Current Android seen-cursor/quiet updates require decoded events, and the elapsed guard requires a summary; document and test an independent correction before changing the worker |

Unknown limits include maximum safe simultaneous live channels, calibrated live oxygen,
measured per-generation historical byte capacity, universal first-sample latency and stable Gen5
bulk-transfer goodput. These remain explicitly unknown rather than guessed UI promises.
