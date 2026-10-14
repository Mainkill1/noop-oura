# Oura ring key transfer (Android)

Keep the source app installed until the exported XML has been imported successfully. Uninstalling
an app removes its Android Keystore key; a raw encrypted preferences XML alone cannot reconstruct it.
This tool is for the user's own NOOP ring credentials. No ring reset, provisioning, or Bluetooth
commands are involved.

## Root exporter APK

1. Install **Oura-Key-Exporter.apk** alongside the source NOOP app.
2. Open **Oura Key Exporter**. Check the source package: typically `com.noop.whoop`,
   `com.noop.whoop.debug`, or `com.noop.whoop.staging`.
3. Tap **Export ring keys** and grant the exporter root access in the root manager.
4. Save `noop_oura_secure_prefs.xml` through Android's document picker.

The exporter has no network permission. It reads only the selected package's Oura preferences,
drops the helper process to that app's UID, opens the existing Android Keystore key, and decrypts
the AndroidX preference envelopes using Tink. It never creates or replaces a source master key.
The source app's files and ring are unchanged. Root-manager and SELinux policies may prevent this
helper from accessing Keystore on some devices; a failed export leaves the original credentials intact.
The helper uses `app_process` without `--application`, keeping credential output in a private pipe
rather than Android's application log stream. Screenshots of the exporter are blocked.

## Import into NOOP

1. Install **NOOP-Oura.apk**. This prerelease uses the existing debug package/signature and updates
   the development build used in this session. It installs alongside official and staging builds.
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
- The separate root-manager authorization path requires a grant for `com.noop.oura.keyexporter`.
  This phone currently denies it; a complete cross-app root export has not yet been verified.
