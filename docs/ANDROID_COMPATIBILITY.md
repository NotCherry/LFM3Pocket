# Android Compatibility & Sideloading Guide (Android 12–17)

This guide documents Hanami's Android platform support matrix, runtime permission gates, and troubleshooting steps for sideloading APKs on modern Android releases—especially **Xiaomi HyperOS (Android 15–17)**.

For architectural rationale and the phased engineering roadmap, see [ADR 0026](adr/0026-android-12-to-17-compatibility-and-permission-gates.md) and [`docs/plans/android-12-to-17-compatibility-plan.md`](plans/android-12-to-17-compatibility-plan.md).

---

## 1. Platform Support Matrix

| Parameter | Baseline (`<= v0.20.4`) | Current (`main` / Next Release) |
| --- | --- | --- |
| `applicationId` | `dev.notcherry.hanami` | `dev.notcherry.hanami` |
| `minSdk` | `26` (Android 8.0) | `26` |
| `compileSdk` / `targetSdk` | `35` / `35` (Android 15) | `35` / `35` (16 KB hardened; staged `36` → `37`) |
| Phone ABI | `arm64-v8a` (`armv8.2-a+dotprod`, [ADR 0001](adr/0001-arm64-dotprod-and-kv-residency.md)) | `arm64-v8a` |
| Native 16 KB page-size compliance | Gap (`v0.20.4`: 3 `.so` libs 4 KB aligned; `useLegacyPackaging true`) | **Full 16 KB ELF + ZIP alignment** (`useLegacyPackaging false`, NDK/Rust 16 KB flags, `onnxruntime-android:1.23.2`, CI-gated) |
| APK Signature Schemes | v2 only (`v3=false`) | **v2 + v3** (CI-gated on signed releases) |

---

## 2. Permission & Capability Gate Reference

Hanami is offline-first: core local chat with a user-imported GGUF model requires **no runtime permissions**. Every sensitive permission is opt-in and gated by Android API level:

| Permission / Capability | First Enforced | Used By | Behavior When Denied |
| --- | --- | --- | --- |
| `POST_NOTIFICATIONS` | Android 13 (API 33) | Persistent model-residency notification, download progress, local reminders (`set_reminder`) | Chat continues in foreground; shade controls and reminders warn that notifications are disabled. |
| `RECORD_AUDIO` | Android 6+ (API 23) / FGS mic gate API 34+ | Push-to-talk & hands-free voice (`ModelRuntimeService` mic FGS) | Voice capture unavailable; text chat and `SPECIAL_USE` model residency unaffected. |
| `CAMERA` (`required="false"`) | Android 6+ (API 23) | In-app photo capture (`CameraCaptureActivity`) | Photo picker / SAF image import remains available. |
| `READ_CALENDAR` / `WRITE_CALENDAR` | Android 6+ (API 23) | Calendar tools (`list_events`, `create_event`, `update_event`, `delete_event`) | Calendar tools fail closed; writes also require a Settings-picked writable calendar ([ADR 0014](adr/0014-calendar-tools-picked-calendar.md)). |
| `READ_CONTACTS` | Android 6+ (API 23) / CP2 strict SQL API 37+ | Offline `lookup_contact` tool (`AndroidContactsGateway`) | Lookup fails closed without querying `ContactsContract`. |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Special access (Android 13+ **Restricted Settings** applies to sideloaded APKs) | Opt-in `list_notifications` ([ADR 0018](adr/0018-notification-list-opt-in.md)) | Off by default; requires master toggle + non-empty allowlist + system Notification Access. |
| `BIND_VOICE_INTERACTION` / `BIND_RECOGNITION_SERVICE` | System Assistant role | Optional default assistant entry (`AssistEntryActivity`) | App remains fully usable via launcher icon and share targets. |
| `FOREGROUND_SERVICE_*` (`specialUse`, `mediaPlayback`, `microphone`, `dataSync`) + `RUN_USER_INITIATED_JOBS` | Android 14 (API 34) / `dataSync` 6h timeout API 35+ | Background model residency (`ModelRuntimeService`) & model downloads (`ModelDownloadJobService` UIDT on API 34+) | Refused FGS promotions degrade gracefully to foreground-only operation. |
| `ACCESS_LOCAL_NETWORK` *(planned for `targetSdk 37`)* | Android 17 (API 37) | Wi-Fi Log Viewer (`LanDiagnosticsServer`), LAN MCP servers (`192.168.x.x` / `10.x.x.x` / `.local`), LAN SearXNG | `127.0.0.1` loopback (e.g., on-device Termux MCP) continues to work without this permission; LAN features prompt with rationale and degrade cleanly if denied. |

---

## 3. Troubleshooting APK Install Failures on Xiaomi HyperOS & Android 15–17

If a tester sees **"App not installed"**, **"Package conflicts"**, or **"Unsafe app blocked"** on a Xiaomi phone (HyperOS 2/3/4 on Android 15–17), check these causes in order:

### Cause A — 16 KB Memory Page Size Device (Android 15–17 Flagships)
Many Android 15–17 flagships (including recent Snapdragon 8 Elite Xiaomi devices) run a 16 KB kernel page size or enforce 16 KB native-library checks.
- **Check on device (via ADB):**
  ```bash
  adb shell getconf PAGESIZE
  ```
  - `4096` = 4 KB kernel; `16384` = 16 KB kernel.
- **Status:** Releases up to `v0.20.4` contain three 4 KB-aligned `.so` files (`liblfm3pocket.so`, `libhanami_dizione.so`, `libonnxruntime4j_jni.so`) and `useLegacyPackaging true`. Wait for the Phase 1 16 KB-aligned release (tracked by [ADR 0026](adr/0026-android-12-to-17-compatibility-and-permission-gates.md)).

### Cause B — Wrong APK Flavor or Signing Key Conflict
1. **Verify the APK asset name:** Phone installs **must** use `Hanami-v<version>-arm64-v8a.apk` from [GitHub Releases](https://github.com/NotCherry/LFM3Pocket/releases) — **not** the `x86_64` `smoke` emulator APK and **not** an unsigned build.
2. **Signature mismatch:** Android refuses to install a signed `v*` release over a previously installed debug APK (`Hanami-latest-arm64-debug.apk`) or unsigned build with the same `dev.notcherry.hanami` package ID.
   - **Fix:** Uninstall the previous `Hanami` app first (Settings → Apps → Hanami → Uninstall), then install the signed release APK.

### Cause C — Xiaomi HyperOS Security Scan / Google Play Protect
Xiaomi's Package Installer (HyperOS Security Center / GetApps) and Google Play Protect aggressively flag sideloaded APKs that declare sensitive capabilities (`BIND_VOICE_INTERACTION`, `BIND_NOTIFICATION_LISTENER_SERVICE`, `RECORD_AUDIO`, `READ_CONTACTS`, `READ_CALENDAR`).
- **On-device install:**
  1. Ensure **Install unknown apps** is enabled for the browser / file manager / Obtainium used to open the APK.
  2. When Play Protect shows *"Unsafe app blocked"* or *"App not recognized"*, tap **More details → Install anyway** (only after verifying the APK SHA-256 against the release `.sha256` file!).
  3. On HyperOS, if the Xiaomi Security installer silently aborts, try installing via **Obtainium** or `adb install` (note: HyperOS requires enabling **USB debugging** *and* **Install via USB** in Developer Options; otherwise ADB fails with `INSTALL_FAILED_USER_RESTRICTED`).

### Cause D — Android 17 "Apps from Unverified Developers" Sideloading Gate
Starting with Android 17 (and late-2026 Developer Verification rollouts), direct sideloading of APKs whose signing key is not registered in Google's Android Developer Verification program may be blocked by default in the system installer.
- **Workarounds on Android 17:**
  - **ADB install:** `adb install Hanami-v<version>-arm64-v8a.apk` bypasses the consumer sideloading gate for development/testing.
  - **On-device advanced flow:** Settings → System → Developer options → **Apps from Unverified Developers** (requires PIN/biometric confirmation, reboot, and Android 17's 24-hour cooldown before selecting *Allow temporarily* or *Allow indefinitely*).

### Cause E — Android 13–17 "Restricted Settings" (After Install)
Even after a clean install, Android 13–17 blocks sideloaded apps from enabling **Notification Access** (`list_notifications`).
- **Fix:** Open **Settings → Apps → Hanami → ⋮ (top-right menu) → Allow restricted settings**, authenticate, and then enable Notification Access for Hanami.

---

## 4. Capturing an Exact Install Failure Code for Bug Reports

Generic `"App not installed"` dialogs hide the true `PackageManager` failure code. When reporting an install failure (using the **Device / Install compatibility** GitHub issue template), capture the exact reason via ADB if a computer is available:

```bash
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abilist
adb shell getconf PAGESIZE
adb install -r Hanami-v<version>-arm64-v8a.apk
```

Include the exact `Failure [INSTALL_...]` line (for example `INSTALL_FAILED_NO_MATCHING_ABIS`, `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, `INSTALL_FAILED_USER_RESTRICTED`, `INSTALL_PARSE_FAILED_NO_CERTIFICATES`, or `INSTALL_FAILED_INVALID_APK`) in the GitHub issue.
