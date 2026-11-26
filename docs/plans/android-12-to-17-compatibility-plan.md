# Plan & Requirements: Android 12–17 Compatibility, 16 KB Packaging, and Permission Gates

- **Status:** Phases 0–2 implemented (Phases 3–5 staged for API 35+ emulator, `targetSdk 36`, `targetSdk 37`)
- **Date:** 2026-11-26
- **Governing ADR:** [ADR 0026 — Android 12–17 compatibility, 16 KB page-size packaging, and versioned permission gates](../adr/0026-android-12-to-17-compatibility-and-permission-gates.md)
- **Related Docs:** [`ANDROID_COMPATIBILITY.md`](../ANDROID_COMPATIBILITY.md), [`RELEASING.md`](../RELEASING.md), [`DIAGNOSTICS.md`](../DIAGNOSTICS.md), [ADR 0027](../adr/0027-kotlin-first-adoption-and-incremental-migration.md)

---

## 1. Problem Statement & Forensic Findings (`v0.20.4`)

Hanami (`dev.notcherry.hanami`, `minSdk 26`, `compileSdk 35`, `targetSdk 35`) installs and runs cleanly on the author's Realme 7 Pro (`RMX2170`, Android 12 / API 31), and CI instrumentation runs exclusively on an `x86_64` Android 11 (API 30) emulator. When tested on Xiaomi phones running Android 15 (HyperOS 2/3) and Android 17 (HyperOS 4 / preview), APK installation failed.

Forensic inspection of the published `Hanami-v0.20.4-arm64-v8a.apk` artifact (`readelf -lW`, `apksigner verify --verbose`, `aapt2 dump badging`, `aapt2 dump xmltree`, and ZIP header offset analysis) revealed the following hard defects and platform gaps:

### 1.1 Confirmed Binary & Manifest Defects in `Hanami-v0.20.4-arm64-v8a.apk`

| # | Defect | Exact Evidence in `v0.20.4` | Impact on Android 15–17 / Xiaomi |
| --- | --- | --- | --- |
| **D-01** | **Three `arm64-v8a` `.so` libraries have 4 KB (`0x1000`) ELF `PT_LOAD` alignment** | `liblfm3pocket.so` (`0x1000`), `libhanami_dizione.so` (`0x1000`), `libonnxruntime4j_jni.so` (`0x1000`). The other 6 `.so` files (`libc++_shared.so`, `libonnxruntime.so`, AndroidX/CameraX) are `0x4000`. | Fails 16 KB page-size validation on Android 15+ 16 KB devices; causes install/load failures or compatibility-mode warnings on Android 16/17. |
| **D-02** | **Legacy compressed JNI packaging (`extractNativeLibs="true"`)** | `app/build.gradle:173` sets `useLegacyPackaging true`. All 9 `.so` entries are `DEFLATED(8)` with unaligned ZIP offsets (`4K=False`, `16K=False`). | Prevents direct 16 KB `mmap` from APK, triggers 16 KB tooling/installer rejections, and doubles on-device native library storage. |
| **D-03** | **Missing APK Signature Scheme v3** | `apksigner verify` reports `v1=false`, `v2=true`, **`v3=false`**, `v3.1=false`. | Lacks modern v3 signing metadata expected by Android 9+ / 15–17 verification and key-rotation flows. |
| **D-04** | **Implied required `android.hardware.microphone` feature** | `aapt2 dump badging` reports `uses-feature: name='android.hardware.microphone'` (implied by `RECORD_AUDIO`), whereas camera features are explicitly `required="false"`. | Can block installation on tablets/devices without an exposed microphone feature flag. |
| **D-05** | **Android 15/16/17 OEM & OS sideloading friction undocumented** | Xiaomi HyperOS Security Center, Play Protect, Android 13+ Restricted Settings (`NotificationListenerService`), and Android 17 "Apps from Unverified Developers" 24-hour unlock flow can reject sideloaded APKs with generic `"App not installed"` messages (plus signature mismatch if upgrading over debug/unsigned APKs). | Testers hit opaque install failures without knowing whether the block is 16 KB alignment, signature conflict, Play Protect, or Android 17 unverified-developer policy. |
| **D-06** | **Zero CI emulator coverage above API 30** | `.github/workflows/build-apk.yml:268,290` runs all emulator E2E jobs on `api-level: 30` (Android 11). | Never exercises Android 12 FGS restrictions, Android 13 `POST_NOTIFICATIONS` / `RECEIVER_NOT_EXPORTED`, Android 14 FGS types / UIDT, Android 15 16 KB / edge-to-edge, Android 16 predictive back, or Android 17 `ACCESS_LOCAL_NETWORK`. |

---

## 2. Release-by-Release Delta Matrix (Android 12 → Android 17)

| Android Version (API) | Platform & Permission Changes | Current Hanami Status | Required Action |
| --- | --- | --- | --- |
| **Android 12 (API 31)** | Explicit `android:exported` required; `PendingIntent` mutability flags required; background FGS starts restricted (`ForegroundServiceStartNotAllowedException`); exact alarm permission split (`SCHEDULE_EXACT_ALARM`). | All manifest components declare `android:exported`; `ModelRuntimeService` catches `IllegalStateException`; reminders use inexact WorkManager (ADR 0007). | Keep verified; add `exported` invariant to CI APK verifier. |
| **Android 13 (API 33)** | Runtime `POST_NOTIFICATIONS` permission; `Context.RECEIVER_NOT_EXPORTED` required for dynamic receivers; granular media permissions (`READ_MEDIA_*`); **Restricted Settings** blocks sideloaded apps from enabling `NotificationListenerService`. | `POST_NOTIFICATIONS` requested in `MainActivity`; `ModelRuntime` uses `RECEIVER_NOT_EXPORTED`; media uses SAF/Photo Picker (no `READ_MEDIA_*` needed). **Gap:** `NotificationAccessSettingsSection` does not explain Android 13+ Restricted Settings unlock steps for sideloaded APKs. | Centralize `POST_NOTIFICATIONS` in `AndroidPermissionGate`; add Restricted Settings guidance in Settings & docs. |
| **Android 14 (API 34)** | Mandatory FGS types (`specialUse`, `mediaPlayback`, `microphone`, `dataSync`) + matching permissions; `RUN_USER_INITIATED_JOBS` (UIDT); `SecurityException` if `microphone` FGS starts without `RECORD_AUDIO`; Safer DCL for DEX/JAR; minimum installable `targetSdk >= 23`. | `ModelRuntimeService` declares `specialUse\|mediaPlayback\|microphone` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`; `ModelDownloadJobService` uses UIDT on API 34+. **Gap:** ensure `microphone` FGS type is never requested unless `RECORD_AUDIO` is granted and app is in eligible state. | Enforce `RECORD_AUDIO` precondition inside `AndroidPermissionGate` before promoting FGS with `FOREGROUND_SERVICE_TYPE_MICROPHONE`. |
| **Android 15 (API 35)** | **16 KB memory page sizes** for 64-bit native code; `dataSync` FGS 6-hour/24h timeout (`Service.onTimeout`); edge-to-edge enforced by default (`setStatusBarColor` / `setNavigationBarColor` deprecated & no-op); audio focus restricted to top app / FGS; `BOOT_COMPLETED` FGS restrictions; OpenJDK `List.removeFirst`/`removeLast` Kotlin collision trap (`NewApi` lint). | **Broken:** 3 `.so` files are 4 KB aligned + `useLegacyPackaging true`. `StillwaterUi.java:77-78` and `ComposeHostActivity.kt:109-110` call deprecated `setStatusBarColor`/`setNavigationBarColor`. `ModelRuntimeService` overrides `onTimeout`. `AndroidPlaybackFocus` ignores `AUDIOFOCUS_REQUEST_FAILED` caller feedback in some paths. | **P0:** Fix 16 KB ELF + ZIP alignment (`useLegacyPackaging false`, CMake, Rust, ONNX `1.22.0+`). Guard status/nav bar color calls to `< 35` or use `enableEdgeToEdge()`. Verify `dataSync` WorkManager fallback stays below 6h and handles stop cleanly. Enable `NewApi` lint check. |
| **Android 16 (API 36)** | Edge-to-edge opt-out removed; **Predictive Back enabled by default** (`onBackPressed` / `KEYCODE_BACK` not dispatched without migration or opt-out); JobScheduler quotas tightened for top-started & FGS-concurrent jobs (`WorkInfo.getStopReason()`); 16 KB compatibility mode warning dialog for 4 KB apps; large-screen (`sw >= 600dp`) orientation/resizability restrictions ignored (temporary opt-out only); `Safer Intents` (`intentMatchingFlags`); opt-in Local Network Permission preview. | No custom `onBackPressed` overrides found (good!), but `HistoryActivity` / `MainActivity` / `ToolConfirmationActivity` should wire `OnBackInvokedCallback` / Compose `BackHandler` cleanly (e.g., Settings panel back-to-chat, History detail back-to-list, ToolConfirmation reject-on-back). `ModelDownloadJobService` already reads `getStopReason()`. | Stage B (`targetSdk 36`): Wire predictive back (`BackHandler` / `OnBackPressedDispatcher`), audit WorkManager/JobScheduler stop reasons in diagnostics, verify tablet/foldable (`sw >= 600dp`) layouts, evaluate `intentMatchingFlags="enforceIntentFilter"`. |
| **Android 17 (API 37)** | **Mandatory `ACCESS_LOCAL_NETWORK`** runtime permission (`NEARBY_DEVICES` group) for all RFC1918 / link-local / `.local` LAN TCP & UDP sockets (inbound & outbound; `127.0.0.1` loopback exempt within same profile, cross-profile loopback blocked); **Background audio hardening** (background playback, audio focus, volume APIs fail silently / `AUDIOFOCUS_REQUEST_FAILED` unless FGS has WIU capabilities); **App memory limits** (`ApplicationExitInfo` `REASON_OTHER` + `"MemoryLimiter:AnonSwap"`); **Contacts Provider 2 (CP2)** PII column restriction & strict SQL when queried without `READ_CONTACTS`; **Safer Native DCL** (`System.load()` must be read-only); **Mandatory large-screen adaptivity** (`sw >= 600dp`, opt-out removed); **NPU feature declaration** (`FEATURE_NEURAL_PROCESSING_UNIT`); `usesCleartextTraffic` deprecation plan; `StrictMode.detectImplicitUriPermissionGrant()` warning (enforced in Android 18); `setContentCaptureEnabled` deprecated in favor of `FLAG_SECURE`; lock-free `MessageQueue` & unmodifiable `static final` fields. | **`LanDiagnosticsServer`** binds all interfaces and accepts LAN TCP connections → **will fail on API 37** without `ACCESS_LOCAL_NETWORK`! **`McpClient`** and **`WebSearch`** can connect to LAN IPs (`192.168.x.x`, `10.x.x.x`, `.local`) → **will fail with `EPERM`/`ECONNABORTED`** without `ACCESS_LOCAL_NETWORK`! `AndroidContactsGateway` catches `RuntimeException` silently instead of checking `READ_CONTACTS` up front. `CrashLog` does not highlight `"MemoryLimiter:AnonSwap"`. `AndroidManifest.xml:50` still declares `usesCleartextTraffic="false"` alongside `network_security_config.xml`. | Stage A: Pre-check `READ_CONTACTS` in `AndroidContactsGateway`; highlight `MemoryLimiter:AnonSwap` in `CrashLog`; audit all `ACTION_SEND`/`ACTION_IMAGE_CAPTURE` intents for explicit `FLAG_GRANT_*_URI_PERMISSION`. Stage C (`targetSdk 37`): Declare & gate `ACCESS_LOCAL_NETWORK` in `AndroidPermissionGate` for LAN diagnostics/MCP/SearXNG; verify WIU FGS for background speech; drop redundant `usesCleartextTraffic="false"`; add optional `FEATURE_NEURAL_PROCESSING_UNIT` if spike `#164` uses NPU. |

---

## 3. Requirements Specification

### 3.1 Packaging, Signing & Native 16 KB Requirements

- **`REQ-COMPAT-01` (16 KB ELF Alignment):** Every `.so` file packaged in `phone`, `smoke`, and `deviceCheck` APKs (`arm64-v8a` and `x86_64`) **must** have ELF `PT_LOAD` `p_align >= 0x4000` (16,384 bytes).
- **`REQ-COMPAT-02` (Uncompressed 16 KB ZIP Alignment):** `app/build.gradle` **must** set `packaging { jniLibs { useLegacyPackaging false } }` so that `AndroidManifest.xml` merges `android:extractNativeLibs="false"` and every `.so` ZIP entry is `STORED` at an offset divisible by 16,384.
- **`REQ-COMPAT-03` (ONNX Runtime 16 KB Upgrade):** `com.microsoft.onnxruntime:onnxruntime-android` **must** be upgraded from `1.20.0` to a verified 16 KB-aligned release (`1.22.0` or newer) while preserving tiny-voca English TTS functionality and unit/instrumentation tests.
- **`REQ-COMPAT-04` (Rust `hanami_dizione` 16 KB Linker Flags):** `scripts/build-dizione-jni.sh` **must** pass `-C link-arg=-Wl,-z,max-page-size=16384` (and link against API `26` to match `minSdk 26`) for both `aarch64-linux-android` and `x86_64-linux-android`.
- **`REQ-COMPAT-05` (CMake / NDK 16 KB Flags):** `app/build.gradle` and `app/src/main/cpp/CMakeLists.txt` **must** pass `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` and `-Wl,-z,max-page-size=16384` so `liblfm3pocket.so` (including linked `llama`, `mtmd`, and `libcactus.a`) is 16 KB aligned.
- **`REQ-COMPAT-06` (Dual v2 + v3 APK Signing):** `signingConfigs.release` in `app/build.gradle` **must** explicitly set `enableV2Signing true` and `enableV3Signing true`. Signed releases **must** pass `apksigner verify` confirming both v2 and v3 signatures.
- **`REQ-COMPAT-07` (Optional Hardware Features):** `AndroidManifest.xml` **must** declare `<uses-feature android:name="android.hardware.microphone" android:required="false" />` alongside existing camera `required="false"` declarations so no hardware-feature filter blocks install.
- **`REQ-COMPAT-08` (Automated CI APK Compatibility Gate):** A self-tested Python gate (`.github/scripts/assert-apk-android-compat.py`) **must** run in both `build-apk.yml` and `release.yml`, failing CI on any 4 KB `.so`, compressed/unaligned `.so`, `extractNativeLibs="true"`, missing v2/v3 signature (when signed), or implied required mic/camera feature.

### 3.2 Permission Gate & Runtime Requirements

- **`REQ-COMPAT-09` (Centralized `AndroidPermissionGate`):** All SDK-versioned permission checks, FGS type eligibility rules, and local-network gating logic **must** be centralized in a Kotlin module (`AndroidPermissionGate.kt`, per [ADR 0027](../adr/0027-kotlin-first-adoption-and-incremental-migration.md)) with pure JVM unit tests covering API levels 26, 30, 31, 33, 34, 35, 36, and 37.
- **`REQ-COMPAT-10` (Android 13+ Restricted Settings UX):** Settings → Notification Access **must** display concise, localized (EN + PL) instructions explaining how to enable "Allow restricted settings" in App Info when Notification Access is greyed out on sideloaded Android 13–17 installs (including Xiaomi HyperOS).
- **`REQ-COMPAT-11` (Android 14+ Microphone FGS Precondition):** `ModelRuntimeService` **must** consult `AndroidPermissionGate` so `FOREGROUND_SERVICE_TYPE_MICROPHONE` is only included in the FGS mask when `RECORD_AUDIO` is granted; otherwise it falls back cleanly to `SPECIAL_USE` without throwing `SecurityException`.
- **`REQ-COMPAT-12` (Android 15+ Edge-to-Edge & System Bars):** Replace deprecated `window.setStatusBarColor(...)` / `window.setNavigationBarColor(...)` calls on API 35+ with API-guarded handling / `WindowCompat` / `enableEdgeToEdge()`, preserving dark Stillwater contrast and verified inset padding across `ComposeHostActivity`, `HistoryActivity`, `MainActivity`, `ToolConfirmationActivity`, and `CameraCaptureActivity`.
- **`REQ-COMPAT-13` (Android 16 Predictive Back & Adaptive Layouts):** When bumping to `compileSdk 36` / `targetSdk 36`, wire `OnBackPressedDispatcher` / Compose `BackHandler` for in-activity back transitions (Settings → Chat, History detail → list, `ToolConfirmationActivity` → reject) and verify zero layout clipping on `sw >= 600dp` screens and orientation changes.
- **`REQ-COMPAT-14` (Android 17 `ACCESS_LOCAL_NETWORK` Gate):** Before bumping to `targetSdk 37`, declare `android.permission.ACCESS_LOCAL_NETWORK` and implement host classification (`isLanHost(host)`: RFC1918, `169.254.0.0/16`, `100.64.0.0/10`, IPv6 link-local, `.local` — explicitly **excluding** `127.0.0.1` / `localhost` / `::1`). On API 37+, `LanDiagnosticsServer` startup and LAN MCP / LAN SearXNG calls **must** request `ACCESS_LOCAL_NETWORK` with a clear rationale and handle denial cleanly (no raw `EPERM` crashes).
- **`REQ-COMPAT-15` (Android 17 Background Audio Hardening):** Spoken response playback and audio focus requests (`AndroidPlaybackFocus`, `PlaybackController`) **must** verify foreground or active WIU FGS eligibility, propagate `AUDIOFOCUS_REQUEST_FAILED` cleanly to status/diagnostics, and degrade to text without crashing or looping silently.
- **`REQ-COMPAT-16` (Android 17 `MemoryLimiter:AnonSwap` Diagnostics):** `CrashLog.captureExitInfo()` **must** detect `"MemoryLimiter:AnonSwap"` in `ApplicationExitInfo.getDescription()` and tag it in the local crash log with an actionable human-readable note (suggesting smaller quantization / lower context length).
- **`REQ-COMPAT-17` (Contacts Provider 2 Pre-Check):** `AndroidContactsGateway` **must** verify `READ_CONTACTS` permission before querying `ContactsContract.CommonDataKinds.*`, and **must never** project restricted CP2 PII columns (`ACCOUNT_NAME`, `ACCOUNT_TYPE`, `ACCOUNT_TYPE_AND_DATA_SET`).
- **`REQ-COMPAT-18` (Explicit URI Permission Grants):** Audit all outgoing `ACTION_SEND`, `ACTION_SEND_MULTIPLE`, `ACTION_VIEW`, and camera/file intents to ensure explicit `FLAG_GRANT_READ_URI_PERMISSION` (and `FLAG_GRANT_WRITE_URI_PERMISSION` where needed), verified with `StrictMode.detectImplicitUriPermissionGrant()` in debug builds.

---

## 4. Phased Implementation Plan

### Phase 0 — Immediate Triage & Tester Diagnostics (Docs Only — Current Turn)
1. Publish [ADR 0026](../adr/0026-android-12-to-17-compatibility-and-permission-gates.md), this plan, and [`docs/ANDROID_COMPATIBILITY.md`](../ANDROID_COMPATIBILITY.md).
2. Share the Xiaomi HyperOS / Android 15–17 install checklist with the colleague to capture the exact `adb install` error code and `adb shell getconf PAGESIZE` on both Xiaomi devices.

### Phase 1 — P0 Install Fix: 16 KB Alignment, Packaging, Signing & CI Gate (`targetSdk 35`)
**Goal:** Produce a 100% 16 KB-compliant, v2+v3-signed APK that installs cleanly on Android 12–17 devices (including 16 KB Xiaomi 15/17 hardware).

| File | Concrete Change |
| --- | --- |
| `app/build.gradle` | Add `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` to CMake args; set `packaging { jniLibs { useLegacyPackaging false } }`; upgrade `onnxruntime-android` from `1.20.0` to `1.22.0` (or latest 16 KB-verified); add `enableV2Signing true` and `enableV3Signing true` to `signingConfigs.release`. |
| `app/src/main/cpp/CMakeLists.txt` | Add `-Wl,-z,max-page-size=16384` to `target_link_options(lfm3pocket PRIVATE ...)`. |
| `scripts/build-dizione-jni.sh` | Export `CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"` and `CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"`; use `android26-clang` to match `minSdk 26`. |
| `app/src/main/AndroidManifest.xml` | Add `<uses-feature android:name="android.hardware.microphone" android:required="false" />`. |
| `.github/scripts/assert-apk-android-compat.py` | New self-tested Python script validating ELF `p_align >= 0x4000`, `STORED` + 16 KB ZIP offsets, `extractNativeLibs="false"`, v2+v3 signatures, `exported` attributes, and optional mic/camera features. |
| `.github/workflows/build-apk.yml` & `.github/workflows/release.yml` | Run `assert-apk-android-compat.py --self-test` in unit job and verify built APKs before staging/publishing releases. |

### Phase 2 — Centralized `AndroidPermissionGate` & Runtime Hardening (`targetSdk 35`)
**Goal:** Consolidate SDK-versioned permission logic in Kotlin and fix Android 13–17 runtime edge cases without waiting for a `targetSdk` bump.

| File | Concrete Change |
| --- | --- |
| `app/src/main/java/com/notcherry/lfm3pocket/AndroidPermissionGate.kt` | New Kotlin policy engine (per ADR 0027) covering notification, mic FGS, UIDT downloads, contacts pre-check, Restricted Settings hint detection (`sdkInt >= 33`), and LAN host classifier (`isLanAddress`). |
| `app/src/test/java/com/notcherry/lfm3pocket/AndroidPermissionGateTest.kt` | Pure JVM unit tests verifying every gate across API 26, 30, 31, 33, 34, 35, 36, 37. |
| `app/src/main/java/com/notcherry/lfm3pocket/AndroidContactsGateway.java` | Pre-check `READ_CONTACTS` via `AndroidPermissionGate` before querying `ContactsContract`. |
| `app/src/main/java/com/notcherry/lfm3pocket/CrashLog.java` | Detect and annotate `"MemoryLimiter:AnonSwap"` exit descriptions in `captureExitInfo()`. |
| `app/src/main/java/com/notcherry/lfm3pocket/StillwaterUi.java` & `ComposeHostActivity.kt` | Guard `setStatusBarColor`/`setNavigationBarColor` to `SDK_INT < 35`; ensure clean edge-to-edge on API 35+. |
| `app/src/main/java/com/notcherry/lfm3pocket/IntegrationsSettingsSection.java` + `strings.xml` (EN/PL) | Add concise Restricted Settings unlock hint for Android 13+ sideloaded installs. |
| `app/src/main/java/com/notcherry/lfm3pocket/ModelRuntimeService.java` | Route FGS type mask construction through `AndroidPermissionGate`. |

### Phase 3 — Modern CI Emulator Lane (API 35 / 16 KB)
1. Add a scheduled/PR-gated emulator job running on **API 35 (or API 36) `x86_64` with 16 KB page-size system image** alongside the existing API 30 backward-compatibility lane.
2. Verify APK install, `System.loadLibrary("lfm3pocket")` + `System.loadLibrary("hanami_dizione")` + ONNX Runtime init, `POST_NOTIFICATIONS` / `RECORD_AUDIO` runtime permission flows, and edge-to-edge visual checks.

### Phase 4 — Android 16 (`compileSdk 36`, `targetSdk 36`) Upgrade
1. Update `compileSdk 36`, `targetSdk 36`, and CI `sdkmanager "platforms;android-36" "build-tools;36.0.0"`.
2. Migrate in-activity back navigation to `OnBackPressedDispatcher` / Compose `BackHandler` so predictive back animations work cleanly.
3. Verify large-screen (`sw >= 600dp`) and rotation state preservation across Home, Chat, Settings, and History.
4. Audit WorkManager / JobScheduler `getStopReason()` telemetry and evaluate `android:intentMatchingFlags="enforceIntentFilter"`.

### Phase 5 — Android 17 (`compileSdk 37`, `targetSdk 37`) Upgrade
1. Update `compileSdk 37`, `targetSdk 37` once AGP & CI SDK packages are available on `ubuntu-latest`.
2. Declare `<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />` and wire runtime requests into `CrashLogSettingsSection` (Wi-Fi Log Viewer), `McpSettingsSection` (LAN MCP endpoints), and `SearchSettingsSection` (LAN SearXNG endpoints) via `AndroidPermissionGate`.
3. Verify background spoken playback against Android 17 WIU FGS audio hardening and log `AUDIOFOCUS_REQUEST_FAILED` cleanly.
4. Remove redundant `android:usesCleartextTraffic="false"` from `AndroidManifest.xml` (fully covered by `network_security_config.xml` on `minSdk 26`).
5. Re-audit `StrictMode.detectImplicitUriPermissionGrant()` across all share/attachment flows.

---

## 5. Verification & Acceptance Criteria

| ID | Criterion | Verification Method |
| --- | --- | --- |
| **`AC-COMPAT-01`** | Every `.so` in `Hanami-v*-arm64-v8a.apk` and `smoke` APK has ELF `PT_LOAD` `p_align >= 0x4000`. | `assert-apk-android-compat.py` + `readelf -lW` in CI. |
| **`AC-COMPAT-02`** | All `.so` entries in release APK are `STORED` with 16 KB-aligned ZIP data offsets and `extractNativeLibs="false"`. | `assert-apk-android-compat.py` in CI. |
| **`AC-COMPAT-03`** | Signed release APK passes `apksigner verify` with both `v2=true` and `v3=true`. | `apksigner verify --verbose` in `release.yml`. |
| **`AC-COMPAT-04`** | `aapt2 dump badging` shows zero implied required `microphone` or `camera` features. | `assert-apk-android-compat.py` in CI. |
| **`AC-COMPAT-05`** | Tiny-voca English TTS synthesizes audio cleanly with upgraded `onnxruntime-android` (`1.23.2`). | Existing JVM + instrumentation TTS tests + ARM64 smoke probe. |
| **`AC-COMPAT-06`** | `AndroidPermissionGateTest` passes 100% across simulated API 26–37 matrices (notifications, FGS types, contacts, LAN vs loopback host classification). | `:app:testSmokeDebugUnitTest` in CI. |
| **`AC-COMPAT-07`** | Release APK installs cleanly on physical Xiaomi Android 15 and Android 17 devices (and Realme `RMX2170` Android 12) and loads `liblfm3pocket.so`, `libhanami_dizione.so`, and `libonnxruntime4j_jni.so` without crash or 16 KB compat dialog. | Physical device evidence report under `device-evidence/reports/`. |
| **`AC-COMPAT-08`** | API 35+ 16 KB emulator CI job installs APK and passes JNI + UI smoke suite. | GitHub Actions workflow run. |
| **`AC-COMPAT-09`** | `CrashLogTest` verifies `"MemoryLimiter:AnonSwap"` exit descriptions are parsed and labeled clearly. | JVM unit test. |
| **`AC-COMPAT-10`** | `AndroidContactsGateway` returns empty/permission-denied cleanly when `READ_CONTACTS` is revoked without issuing an unauthorized `ContentResolver.query`. | JVM unit test. |
| **`AC-COMPAT-11`** | On `targetSdk 36`, predictive back navigates Settings → Chat, History detail → list, and rejects pending `ToolConfirmationActivity` proposals cleanly. | Instrumentation UI test on API 36 emulator. |
| **`AC-COMPAT-12`** | On `targetSdk 37`, `127.0.0.1` Termux MCP works without `ACCESS_LOCAL_NETWORK`, while `LanDiagnosticsServer` and `192.168.x.x` MCP/SearXNG request `ACCESS_LOCAL_NETWORK` and degrade cleanly when denied. | Unit test + API 37 emulator/device test. |

---

## 6. Risk Register

| Risk | Likelihood | Impact | Mitigation |
| --- | --- | --- | --- |
| Larger downloadable APK with `useLegacyPackaging false` | Certain (~15 MB → ~28–32 MB due to uncompressed `libonnxruntime.so` + `liblfm3pocket.so`) | Low–Medium | Installed disk footprint actually *shrinks* (no duplicate extraction). Document in release notes; keep R8/ProGuard + `localeFilters ['en', 'pl']` + `--gc-sections` active. |
| `onnxruntime-android` `1.23.2` API or binary size regression | Low | Medium | Run full tiny-voca synthesis unit/E2E tests and inspect APK size diff before merging Phase 1. |
| Prebuilt `libcactus.a` contains incompatible relocations or 4 KB-only assumptions | Low | High | Static archive `.a` links into `liblfm3pocket.so` with `-Wl,-z,max-page-size=16384`; verify resulting `.so` alignment and run `assert-cactus-runtime-in-apk.py` + ARM64 Whisper/Whistle transcription test. |
| Xiaomi HyperOS / Android 17 sideloading still blocked by Play Protect or Google Developer Verification | Medium | High | Document exact user unblock steps in `ANDROID_COMPATIBILITY.md`; evaluate F-Droid / IzzyOnDroid distribution and Google Developer Verification registration for direct APK signing key. |
