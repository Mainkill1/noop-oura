# Official-app protocol candidates

[Index](Home.md). These are **unvalidated interoperability candidates**, derived by a separate
investigator from the user-authorized official Android 7.27.0 package (261007085) on 2026-10-15.
Base APK SHA-256: `01d7f707ebb9137f7637034c4d881833d7dfe9c1d99b64f645aeecbae291b0aa`.
No operation on this page was sent to the ring during the investigation. No vendor code,
identifiers, assets or firmware are reproduced. Necessary byte/width convergence is limited to
the proposed compatibility contract; it is not a ready command implementation.

## History bundling is a separate negotiation

The package contains a bundling request distinct from MTU negotiation, the general notification
bitmap and per-feature subscriptions. Candidate request bytes are `2f 02 03 01`; the expected
reply uses outer `0x2F`, suboperation `0x04` and a final `0x01` marker. Capability category
`0x05` appears version-sensitive.

This adds a plausible explanation for the earlier official-app packing advantage. It does not
prove that this setting caused that capture's result, that this Gen5 accepts it, that a bundle
is simply concatenated legacy TLVs, or that the option is session-local. **No reversal was
established.** NOOP must leave this candidate unused until reversibility, framing and actual
outcomes are demonstrated through the repository's command-coordination process.

## Extended history has its own framing and time domain

| Property | Existing legacy route | New static candidate |
| --- | --- | --- |
| Request operation | `0x10` | `0x2F/0x41` |
| Request fields after operation | 32-bit ring cursor, 8-bit count, 32-bit selector/flags | 8-bit buffer, 64-bit little-endian ring-millisecond cursor, 16-bit little-endian count |
| Complete request size | 11 bytes | 14 bytes |
| Summary | `0x11` | `0x2F/0x42` |
| Extended summary fields | Not applicable | 16-bit count, 8-bit analysis progress, 32-bit remaining bytes, 8-bit buffer, 8-bit result |
| Summary length | Existing legacy decoder accepts its supported variants | Nine bytes after suboperation; outer body ten bytes including suboperation |
| Data delivery | Legacy record notifications | `0x2F/0x43` fragments containing length-prefixed envelopes |
| App's examined requested count | 255 | Initially 64 |
| Representable count width | 255 | 65,535; field width is not a safe batch-size recommendation |
| Buffer interpretation | Ordinary legacy history | Candidate 0 = ordinary, 1 = separately requested raw-data history |

No summary field was identified as a resume cursor. The new cursor is ring milliseconds,
not an established Unix epoch. Retail generation alone does not select this route: the APK
uses conditional availability, and this review does not prove support on the target ring.

The candidate envelope has an unsigned 16-bit little-endian length prefix. The prefix and
body may cross notification boundaries, and one fragment may contain multiple envelopes.
An assertion that legacy records never fragmented in an earlier corpus cannot be generalized
to this route. A complete envelope also does not mean the whole history transfer is complete.

The envelope's internal event schema, compression/encryption status, calibration and complete
terminal behavior remain unknown. NOOP's current raw parser intentionally rejects incomplete
notifications; adding this route would require a separately validated parser and cursor domain,
not routing its fragments through the legacy decoder.

## Operation timing and interruption

Examined official paths use a candidate **120-second operation watchdog** for both legacy and
extended history. This is an application policy, not a maximum allowed inter-packet gap or
proof of a ring response deadline. It differs from NOOP's 1.5-second quiet boundary and its
summary-evaluated 300-second elapsed guard.

One examined unusable-batch path permits up to three rereads on the same connection, with an
approximately 100 ms scheduling delay. One interruption path keeps the saved cursor behind unfinished
fragments. Important terminal paths failed reconstruction. These findings identify incomplete
envelopes, buffer mismatches, unsupported operations and unusable batches as acceptance cases;
they do not justify copying retries or claiming that every official exit preserves progress.

Legacy request lower-bound semantics and equal-timestamp behavior still need captures.
Representable zero-count requests do not settle acknowledgment or retention semantics.
Neither ordinary reads nor the `0x28` analysis/check operation was proved to consume or erase
history. Keep the uncertainty in [historical extraction](Historical-data-extraction.md).

## Ring-side connection policy

The package maps a one-byte policy operation `0x16` to a `0x17` response:

| Candidate value | Intended policy in this package |
| --- | --- |
| `0x00` | Background |
| `0x01` | Foreground |
| `0x03` | Disconnect-oriented |
| `0x07` | Foreground with lower-latency preference |

These are candidate policy names, not negotiated interval/latency/PHY values. The meaning of
`0x02` from the older official-session capture was not established in this build. The old
“fast-HR” shorthand therefore cannot name every policy reliably. Ring BLE policy, general
ring mode `0x31`, daytime-HR feature mode and Android connection priority remain separate.
Actual speed, power use, persistence and restoration need proof before any new automatic use.

## Additional known categories

The package also contains capability/version pages, feature state/latest/parameter reads,
a wider notification bitmap, deliberate activity sessions, research/raw collection, HR sharing,
and a second vendor-service constant without an attributable active role. Existing extra
Gen5 characteristics still have no established purpose. These are inventory entries rather
than validated available features. Some capability-number labels conflict with older sources;
they do not replace NOOP's canonical table without captures.

Firmware, manufacturing, reset and airplane/power families remain excluded from execution.
External HR-monitor or accessory support does not establish another ring sensor channel.
