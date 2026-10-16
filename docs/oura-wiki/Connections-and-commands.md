# Connections and commands

[Index](Home.md). Wire facts below are summarized from NOOP's existing
[protocol specification](../OURA_PROTOCOL.md), pure command builders and GATT registry.
The presence of an operation in the protocol inventory does not authorize sending it.

## Physical link and application session

| Layer | Known behavior | Expected outcome and limit |
| --- | --- | --- |
| BLE discovery | The phone discovers a connectable ring and the registered active device | A radio address is not the stored identity or proof of authentication |
| OS bond/link security | Android manages pairing and link keys | Independent of the app's 16-byte authentication key; do not replace either to troubleshoot a quiet sensor |
| GATT discovery | Base service `98ED0001-A541-11E4-B6A0-0002A5D5C51B` | Discover current handles and characteristic properties; do not reuse captured ATT handles |
| Phone → ring | Characteristic `98ED0002-A541-11E4-B6A0-0002A5D5C51B` | NOOP uses writes without response, serialized by a single worker |
| Ring → phone | Characteristic `98ED0003-A541-11E4-B6A0-0002A5D5C51B` | Local notification registration plus remote CCCD subscription are needed |
| Gen5 extra channels | Characteristics ending `0004`, `0005`, `0006` in the same UUID family | Discovered in prior research; functions unconfirmed; NOOP leaves them unused |
| ATT MTU | NOOP requests 203 for Gen3 and 247 for Gen4/5 | Requested and negotiated values differ; see [transfer speed](BLE-transfer-speed.md) |
| App authentication | Request a fresh nonce, compute proof using the existing local key, submit proof | A successful session response is needed on every connection; connection alone is insufficient |
| Notification mask | NOOP default `0x3F`; earlier official-app capture used `0xFF` | Mask and sensor subscription are distinct; `0xFF` does not prove faster or additional sensing |

History records and command replies share the notification path. They are not separate parallel
radio connections for each metric. Starting additional pages cannot create unlimited independent
workloads. NOOP reconciles page demand through one connection-owned queue.

Private static inspection additionally identifies a bundling negotiation and an extended
buffer-specific history route. These are **not implemented or validated** by this wiki;
see [official-app candidates](Official-app-protocol-candidates.md) for bytes, limits and conflicts.

## Current operation families

The identifiers are interoperability facts. Payload details, generation caveats and attribution
remain in [protocol sections 3–7](../OURA_PROTOCOL.md).

| Request → response | Purpose | Expected result | Important limit |
| --- | --- | --- | --- |
| `0x08 → 0x09` | Firmware identity | Version information | Does not update firmware |
| `0x18 → 0x19` | Product information pages | Serial/hardware identity | Personal identifiers stay on-device; family must resolve through the active registry |
| `0x1C → 0x1D` | Notification mask | Mask operation response | Not a sensor-mode switch; notification packing effect remains unproven |
| `0x2F/0x2B → 0x2F/0x2C` | Authentication nonce | Fresh challenge | Never expose nonce/proof/key through raw sensor capture |
| `0x2F/0x2D → 0x2F/0x2E` | Authentication proof | Session status | Failure is not permission to install a new key |
| `0x12 → 0x13`, record `0x42` | Time synchronization | Ring/UTC mapping evidence | Ring timestamp is not Unix time; validate current anchor |
| `0x28 → 0x29` | Existing flush/analysis operation | Operation response | Retention/consumption semantics remain unresolved; not documented here as an erase or harmless read |
| `0x10 → 0x11` plus records | Banked-history retrieval | Record stream and remaining-byte summary | Summary can precede the last records; see [history](Historical-data-extraction.md) |
| `0x0C → 0x0D` | Battery/state query | Current battery response | Read-only; write completion and response timing differ |
| `0x2F/0x20 → 0x2F/0x21` | Feature status query | Feature id, mode, status, state and subscription | A mode does not prove actual samples or explain account entitlement |
| `0x2F/0x22 → 0x2F/0x23` | Existing constrained feature-mode operation | Mode-write status | Daytime HR mode 0 disables background measurement; mode 1 is automatic; mode 3 requests foreground live |
| `0x2F/0x26 → 0x2F/0x27` | Feature notification subscription | Subscription-write status | HR subscription 2 requests latest; 0 unsubscribes without itself restoring feature mode |
| `0x2F/0x28` push | Foreground HR/IBI | Actual live sample payloads | Contact, movement and temperature affect availability; no guaranteed first-pulse deadline |
| `0x06 → 0x07`, push `0x33` | Existing raw accelerometer request | Request status, then XYZ notifications if supported | Finite lease, explicit stop, raw signed counts; no calibrated acceleration or ring time inferred |
| `0x20 → 0x21` | User/configuration information | Field operation response | Separate from sensing; NOOP uses only curated documented paths |

## Known protocol surface outside automatic operation

The existing protocol dictionary additionally names self-test (`0x0A/0x0B`), state query
(`0x1E/0x1F`), BLE mode (`0x16/0x17`), ring mode (`0x31/0x32`), flight mode (`0x26`),
manufacturing information (`0x37/0x38`, `0x39/0x3A`), initial key installation (`0x24/0x25`),
reset (`0x1A/0x1B`) and firmware/update traffic (`0x0E/0x0F`, `0x2B`, `0x2C`).
Some names and effects are disputed between references. This list is not evidence that the
installed official version executes each operation, nor a command menu for NOOP. Reset, key
replacement, flight mode, manufacturing and firmware traffic stay outside this investigation.
In particular, the name “BLE mode” is not evidence of a standard 2M-PHY throughput setting.

## Sensor acquisition contexts

| Data | Acquisition context | What NOOP can honestly display |
| --- | --- | --- |
| Daytime HR/IBI `0x80` | Banked green-channel measurements | Validated intervals/quality according to the existing decoder |
| Night HR/optical amplitude `0x60` | Banked nighttime records | Intervals and optical amplitude; amplitude is not a calibrated health score |
| HRV `0x5D` | Ring summary records | Existing HR/RMSSD summary fields |
| Temperature `0x46`, `0x69`, `0x75` | Banked records | Existing validated temperature fields, distinct from phone receipt time |
| Motion `0x47`, `0x6B` | Banked motion/period records | Existing historical units; not interchangeable with live signed16 XYZ |
| Sleep phases `0x4B`, `0x4E`, `0x5A` | Banked phase bursts | Existing validated phase-code decoding and anchoring limits |
| Oxygen-related `0x6E`, `0x6F`, `0x70`, `0x77`, `0x7B` | Banked optical/oxygen families | Per-event decoder/trust limits; not all fields are calibrated percentages |
| Research oxygen `0x8B` | Research optical record | Bytes; uncertain offsets prevent a guessed percentage |
| Research optical `0x81` | Research raw PPG | Bytes; no independently validated cardiovascular-age calculation |
| Live HR `0x2F/0x28` | Foreground requested stream | Actual fresh samples, host receipt time and age |
| Live XYZ `0x33` | Foreground requested raw stream | X/Y/Z together and persistent one-second host summaries |
| Activity/MET, real steps | Generation/configuration-dependent banked records | Explicit trust tiers and availability; catalog presence does not prove emission |

The full tag dictionary, including lifecycle, wear, sleep variants and unvalidated entries,
is maintained in [EventTags.kt](../../android/app/src/main/java/com/noop/oura/EventTags.kt)
and its Swift twin. Unknown bytes must remain unknown instead of being routed through a
similarly named decoder.
