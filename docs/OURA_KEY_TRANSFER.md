# Oura ring key transfer (Android)

Keep the source app installed until the exported XML has been imported successfully. Uninstalling
an app removes its Android Keystore key; a raw encrypted preferences XML alone cannot reconstruct it.
This tool is for the user's own NOOP ring credentials. No ring reset, provisioning, or Bluetooth
commands are involved.

## Root exporter APK

1. Install [Oura Key Exporter 1.0.1](https://github.com/Mainkill1/noop-oura/releases/tag/oura-key-exporter-1.0.1) alongside the source NOOP app.
2. Open **Oura Key Exporter**. Choose the package that already contains the ring key: typically
   `com.noop.whoop`, `com.noop.whoop.debug`, or `com.noop.whoop.staging`. The verified OnePlus 15
   held its existing key in `com.noop.whoop.staging`; the newly installed debug app had no key file.
3. Tap **Export ring keys** and grant the exporter root access in the root manager.
4. Save `noop_oura_secure_prefs.xml` through Android's document picker.

The exporter has no network permission. It reads only the selected package's Oura preferences,
drops the helper process to that app's UID, opens the existing Android Keystore key, and decrypts
the AndroidX preference envelopes using Tink. It never creates or replaces a source master key.
The source app's files and ring are unchanged. Root-manager and SELinux policies may prevent this
helper from accessing Keystore on some devices; a failed export leaves the original credentials intact.
Version 1.0.1 requests `su --mount-master` (Magisk-compatible root) so Android's per-app mount
namespace does not hide the source preferences from the root process. The helper still switches to
the source UID before accessing Keystore. The helper uses `app_process` without `--application`,
keeping credential output in a private pipe
rather than Android's application log stream. Screenshots of the exporter are blocked.

## Import into NOOP

1. Install **NOOP-Oura-12.4.0-release.apk** from the [signed Android release](https://github.com/Mainkill1/noop-oura/releases/tag/v12.4.0).
   It uses package `com.noop.whoop` and a dedicated release signing certificate. Earlier session
   prereleases used `com.noop.whoop.debug`; their keys do not transfer automatically between packages.
   Keep the development app installed until its key export has been imported into the release app.
   An existing `com.noop.whoop` signed by another certificate cannot accept this APK as an
   in-place update; preserve that source installation and export its data and keys first.
2. Tap **Oura**, open the toolbar's **More** (three dots), then **Transfer ring keys**.
3. Choose **Import key XML**, select the exported file, review the ring identifiers, and tap
   **Import listed keys**. Existing credentials for those identifiers are replaced together.
4. When adding the ring, use **Advanced: I already have my ring key**, then **Import key XML** and
   select the matching ring identifier. Continue scanning and connecting with that credential.

The Advanced setup picker lets a transferred key follow the selected ring even if the old app used
a serial identifier and the new scan starts with a Bluetooth address. The key field is masked.
Import does not arm adoption intent. Credentials are stored encrypted in the new app's Keystore-backed
preferences. The XML itself contains readable secrets: keep it private and delete spare copies after use.
The normal NOOP app can also export its own stored keys through **Transfer ring keys** without root.

## Development-to-release migration

Android Keystore credentials belong to an app UID. Copying encrypted preferences from
`com.noop.whoop.debug` to `com.noop.whoop` does not transfer the underlying master key. Use the
export/import flow above before removing the source package. The release signing certificate
fingerprint is supplied with the APK; future release updates must retain that identity.

For the rooted OnePlus 15 installation on 2026-10-15, a private phone-only backup retained the
source APK, databases, files and preferences. Ordinary data was copied with the source stopped.
The existing root exporter decrypted the four stored ring keys as the source UID; a one-shot local
helper imported them through the release app's own encrypted store as its new UID and verified
all four keys in memory. The temporary plaintext XML was then deleted. No key material or health
records were transferred to the build host or GitHub. Target UID ownership and SELinux categories were restored for the copied files. The signed release
authenticated successfully with the unchanged ring key, and its APK digest matches the distributed
asset. The debug package was disabled to prevent competing connections; its data and Keystore remain
available for rollback.

## File compatibility

- Standard Android `<map>` XML, with `<string name="install_key_DEVICE_ID">…</string>` entries.
- Values: native NOOP Base64 encoding of exactly 16 bytes, or a 32-character hexadecimal key.
- Native encrypted AndroidX XML when the importing process has access to its original master key.
  The root exporter supplies the source app identity for this case.
- Multi-ring exports retain their identifiers. Adoption flags and unrelated preferences are excluded.
- Input is limited to 128 KiB. DTDs/entities requiring declarations, duplicate names, malformed XML,
  and invalid key lengths fail before any credentials are committed.

The importer is Android-only because the requested source file and Keystore are Android-specific.
No Apple implementation is changed.

## Verification

- Six JVM tests cover portable round trips, multiple rings, malformed/duplicate/oversized input,
  DTD rejection, native Tink keysets/envelopes, and missing/wrong master keys.
- Thirteen device tests pass on the OnePlus 9 Pro / Android 16. These include actual AndroidX XML
  decryption, the standalone helper running as the source app UID, secure storage and adoption-flag
  removal, transfer controls, dashboard navigation, chart inspection, and the complete stats overview
  with enlarged text.
- Full debug and standalone exporter release builds pass. The exporter also passes release vital lint.
- Root export was verified on a OnePlus 15 (CPH2749), Android 16, using Magisk on 2026-10-14.
  Before the fix, UID 0 in the exporter's inherited mount namespace could not see the staging app's
  preferences; the global namespace could. Version 1.0.1 passed the actual exporter → root helper →
  source-UID Keystore → Android document-save flow. The saved XML matched the independent private
  helper output byte for byte; that temporary copy was then removed. Real key material stayed on
  the phone and was never printed or committed. Exporter release assembly and vital lint pass.
- Root-manager access is required for `com.noop.oura.keyexporter`; other root implementations and
  SELinux policies remain device-dependent. The earlier OnePlus 9 Pro denied its grant; successful
  export on the OnePlus 15 does not establish root access on the other phone.
