# ADR 0026 — Android 12–17 compatibility, 16 KB page-size packaging, and versioned permission gates

- Status: Accepted
- Date: 2026-11-26
- Related: [ADR 0001](0001-arm64-dotprod-and-kv-residency.md), [ADR 0004](0004-local-crash-log.md), [ADR 0006](0006-voice-actions-loop.md), [ADR 0014](0014-calendar-tools-picked-calendar.md), [ADR 0018](0018-notification-list-opt-in.md), [ADR 0022](0022-background-model-downloads.md), [`ANDROID_COMPATIBILITY.md`](../ANDROID_COMPATIBILITY.md), [`android-12-to-17-compatibility-plan.md`](../plans/android-12-to-17-compatibility-plan.md)

## Context

Hanami has historically been verified on a physical Realme 7 Pro (`RMX2170`, Android 12 / API 31) and in CI on an `x86_64` Android 11 (API 30) emulator (`.github/workflows/build-apk.yml`). Field testing of release APKs (`dev.notcherry.hanami`, `compileSdk 35`, `targetSdk 35`, `minSdk 26`) on newer Xiaomi devices running Android 15 (HyperOS 2/3) and Android 17 (HyperOS 4 / developer builds) failed at installation time.

A forensic audit of the published `Hanami-v0.20.4-arm64-v8a.apk` artifact and the codebase identified concrete, reproducible packaging, signing, manifest, and API-level gaps across Android 12–17:

1. **16 KB page-size alignment failures in shipped native libraries (Android 15+ blocker).**
   - Inspecting `Hanami-v0.20.4-arm64-v8a.apk` with `readelf -lW` shows three shipped `arm64-v8a` shared libraries still have 4 KB (`0x1000`) `PT_LOAD` segment alignment instead of 16 KB (`0x4000`):
     - `lib/arm64-v8a/liblfm3pocket.so` (`0x1000`) — `app/src/main/cpp/CMakeLists.txt` does not enable flexible page sizes (`-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` / `-Wl,-z,max-page-size=16384`).
     - `lib/arm64-v8a/libhanami_dizione.so` (`0x1000`) — `scripts/build-dizione-jni.sh` links Rust with `aarch64-linux-android21-clang` without `-Wl,-z,max-page-size=16384`.
     - `lib/arm64-v8a/libonnxruntime4j_jni.so` (`0x1000`) — `com.microsoft.onnxruntime:onnxruntime-android:1.20.0` ships a 4 KB-aligned JNI wrapper (fixed and verified 16 KB-aligned in `1.23.2`).
   - Additionally, `app/build.gradle` sets `packaging { jniLibs { useLegacyPackaging true } }`, forcing `android:extractNativeLibs="true"` and `DEFLATED` `.so` ZIP entries with unaligned offsets instead of uncompressed 16 KB-aligned entries. On 16 KB page-size Android 15–17 devices (including modern Snapdragon 8 Elite / Xiaomi 15 & 17 class hardware), 4 KB-aligned native libraries trigger compatibility dialogs, load failures, or strict installer/store rejections.
2. **Missing APK Signature Scheme v3 (`v2=true`, `v3=false`).**
   - `apksigner verify --verbose` on `Hanami-v0.20.4-arm64-v8a.apk` confirms only v2 signing is present. `signingConfigs.release` in `app/build.gradle` does not explicitly set `enableV3Signing true`. While v2 installs on standard AOSP, v3 is the standard modern signing scheme for Android 9+ key-rotation and strict OEM/installer verification pipelines.
3. **Implied required hardware feature for microphone.**
   - `AndroidManifest.xml` declares `RECORD_AUDIO` without `<uses-feature android:name="android.hardware.microphone" android:required="false" />`. `aapt2 dump badging` confirms `uses-feature: name='android.hardware.microphone'` is implicitly required, whereas camera features are explicitly marked `required="false"`.
4. **OEM / Android 15–17 sideloading & security-policy friction (Xiaomi HyperOS + Play Protect + Android 17 Developer Verification).**
   - Hanami requests a broad surface of sensitive capabilities at manifest level (`BIND_VOICE_INTERACTION`, `BIND_NOTIFICATION_LISTENER_SERVICE`, `RECORD_AUDIO`, `CAMERA`, `READ_CALENDAR`, `WRITE_CALENDAR`, `READ_CONTACTS`, `FOREGROUND_SERVICE_SPECIAL_USE`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_DATA_SYNC`, `RUN_USER_INITIATED_JOBS`).
   - On Xiaomi HyperOS 2/3/4 and Android 15–17, sideloaded APKs with sensitive permissions encounter:
     - Xiaomi Security Center / GetApps / Play Protect install blocks (`App not installed` / `Unsafe app blocked`).
     - Signature mismatches when upgrading over debug (`Hanami-latest-arm64-debug.apk`) or unsigned builds.
     - Android 13–17 Restricted Settings locking `NotificationListenerService` until manually enabled in App Info.
     - Android 17's "Apps from Unverified Developers" sideloading verification flow (including the 24-hour unlock delay for unverified developer keys when not installed via ADB).
5. **Android 12–17 runtime, permission, and platform behavior drift.**
   - CI emulator tests run only on API 30 (Android 11), so API 31–37 runtime gates are never exercised automatically.
   - Targeting Android 16 (API 36) and Android 17 (API 37) introduces breaking runtime gates directly affecting Hanami features:
     - **Android 15 (API 35):** 16 KB page sizes; `dataSync` FGS 6-hour/24h timeout (`Service.onTimeout`); enforced edge-to-edge (deprecated `setStatusBarColor`/`setNavigationBarColor` still called in `StillwaterUi` and `ComposeHostActivity`); strict audio focus rules (`AndroidPlaybackFocus`).
     - **Android 16 (API 36):** Edge-to-edge opt-out removed; **predictive back enabled by default** (`onBackPressed` no longer dispatched without opt-out or `OnBackInvokedCallback`); JobScheduler runtime quotas enforced on top-started and FGS-concurrent jobs; 16 KB compatibility mode dialog for 4 KB apps; large-screen (`sw >= 600dp`) resizability enforcement (temporary opt-out only); `Safer Intents` opt-in; opt-in local network restrictions.
     - **Android 17 (API 37):**
       - **`ACCESS_LOCAL_NETWORK` runtime permission (mandatory for `targetSdk 37+`)**, under the `NEARBY_DEVICES` group. Directly blocks `LanDiagnosticsServer` (incoming LAN TCP), LAN MCP servers (`McpClient`), and local-LAN SearXNG endpoints (`WebSearch`) with `EPERM`/`ECONNABORTED` unless requested and granted! (Loopback `127.0.0.1` within the same profile stays permitted; cross-profile loopback is blocked.)
       - **Background audio hardening:** Background audio playback, audio focus (`AndroidPlaybackFocus`), and volume changes (`AndroidAudioVolume`) fail silently or return `AUDIOFOCUS_REQUEST_FAILED` unless backed by a foreground service with while-in-use (WIU) capabilities.
       - **App memory limits (`MemoryLimiter:AnonSwap`):** Platform-enforced per-app RAM limits kill memory-heavy apps with `ApplicationExitInfo` `REASON_OTHER` + description `"MemoryLimiter:AnonSwap"`. Crucial for multi-gigabyte LFM3 GGUF models; `CrashLog` must recognize and surface this cleanly.
       - **Contacts Provider 2 (CP2) hardening:** Restricts PII columns in `ContactsContract.Data` and enforces strict SQL validation when queried without `READ_CONTACTS` (`AndroidContactsGateway` must verify `READ_CONTACTS` before querying).
       - **Safer Native DCL:** Any dynamic `System.load()` path must be read-only (Hanami uses `System.loadLibrary()` from APK/native lib dir, which remains safe and must stay the rule).
       - **Mandatory large-screen adaptivity (`sw >= 600dp`):** Opt-out removed; rotation/resizing must preserve UI state cleanly.
       - **NPU hardware feature declaration (`FEATURE_NEURAL_PROCESSING_UNIT`):** Required if/when spike `#164` adds direct NPU access targeting API 37.
       - **Explicit URI permission grants (`ACTION_SEND` / `ACTION_SEND_MULTIPLE` / `ACTION_IMAGE_CAPTURE`):** Implicit grants are flagged by `StrictMode.detectImplicitUriPermissionGrant()` on API 37 and removed in Android 18.
       - **`usesCleartextTraffic` deprecation:** `AndroidManifest.xml` already uses `network_security_config.xml` and `minSdk 26`, so `android:usesCleartextTraffic="false"` should be dropped once verified against `network_security_config.xml`.

## Decision

Hanami adopts a structured **Android 12–17 compatibility architecture** built on five pillars:

### 1. Native 16 KB page-size compliance and modern APK packaging (Immediate — Milestone 1)
- Every shipped `arm64-v8a` and `x86_64` `.so` library must have ELF `PT_LOAD` alignment `>= 16384` (`0x4000`):
  - CMake (`app/build.gradle` + `app/src/main/cpp/CMakeLists.txt`): pass `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` and link `lfm3pocket` with `-Wl,-z,max-page-size=16384`.
  - Rust JNI (`scripts/build-dizione-jni.sh`): pass `-C link-arg=-Wl,-z,max-page-size=16384` via `CARGO_TARGET_*_RUSTFLAGS` (and upgrade target linker level to API 26 to match `minSdk 26`).
  - ONNX Runtime (`app/build.gradle`): upgrade `com.microsoft.onnxruntime:onnxruntime-android` from `1.20.0` to a verified 16 KB-aligned release (`1.22.0+`).
  - Cactus (`vendor/CACTUS_*`): verify `libcactus.a` object sections link cleanly into 16 KB-aligned `liblfm3pocket.so`.
- Switch `packaging { jniLibs { useLegacyPackaging false } }` so AGP stores `.so` files uncompressed and 16 KB ZIP-aligned (`android:extractNativeLibs="false"`), enabling zero-copy `mmap` and avoiding extraction overhead/errors on Android 15–17.
- Explicitly enable APK Signature Scheme v2 **and** v3 (`enableV2Signing true`, `enableV3Signing true`) in `signingConfigs.release`.
- Declare `<uses-feature android:name="android.hardware.microphone" android:required="false" />` so devices without a built-in microphone are never rejected at install time.

### 2. Centralized, SDK-versioned permission gates (`AndroidPermissionGate`)
- Replace scattered `Build.VERSION.SDK_INT` / `checkSelfPermission` checks across `MainActivity`, `Lfm3PocketApplication`, `ModelRuntime`, `ModelRuntimeService`, `ModelDownloadManager`, `AndroidContactsGateway`, and `CrashLogSettingsSection` with a single, pure-testable Kotlin policy module (`AndroidPermissionGate.kt`, per [ADR 0027](0027-kotlin-first-adoption-and-incremental-migration.md)) that encodes the exact permission and API-level gates:

| Capability | Min API gated | Manifest permission(s) | Runtime / special gate | Denied / unavailable behavior |
| --- | --- | --- | --- | --- |
| Notifications & FGS visibility | API 33 (Android 13) | `POST_NOTIFICATIONS` | Runtime prompt on first opt-in / background action | Graceful fallback: continue in-foreground chat; warn that shade controls and reminders require notification permission |
| Dynamic broadcast receivers | API 33 (Android 13) | — | Explicit `RECEIVER_NOT_EXPORTED` | Already enforced in `ModelRuntime`; audit all new receivers |
| Background model residency & voice FGS | API 34 (Android 14) | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_MICROPHONE` | Pass exact `ServiceInfo.FOREGROUND_SERVICE_TYPE_*` mask; require `RECORD_AUDIO` **before** adding `MICROPHONE` type | Catch `ForegroundServiceStartNotAllowedException` & `SecurityException`, fall back to `SPECIAL_USE` or foreground-only mode |
| Background model downloads (UIDT / WorkManager) | API 34 (Android 14) / API 35 (Android 15) | `RUN_USER_INITIATED_JOBS`, `FOREGROUND_SERVICE_DATA_SYNC` | Prefer UIDT `ModelDownloadJobService` on API 34+ (avoids `dataSync` 6h timeout); handle `onTimeout(startId, fgsType)` and `WorkInfo.getStopReason()` | Pause/reschedule cleanly; never crash with `RemoteServiceException` |
| Notification summarization (`list_notifications`) | API 33+ sideload Restricted Settings | `BIND_NOTIFICATION_LISTENER_SERVICE` | System Notification Access + in-app master toggle + allowlist; show Restricted Settings unlock hint on Android 13+ | Fail closed (`PERMISSION_DENIED`) with actionable Settings shortcut |
| Calendar tools | API 26+ | `READ_CALENDAR`, `WRITE_CALENDAR` | Runtime prompt + picked writable calendar in Settings | Block reads/writes fail-closed; prompt in Settings |
| Offline contacts lookup | API 26+ / API 37 CP2 strict SQL | `READ_CONTACTS` | Check `READ_CONTACTS` **before** issuing any `ContactsContract` query; avoid restricted PII columns (`ACCOUNT_NAME`, `ACCOUNT_TYPE`) | Return permission-required state; never trigger CP2 strict-SQL exception |
| Local network access (Wi-Fi Log Viewer, LAN MCP, LAN SearXNG) | API 37 (Android 17) | `ACCESS_LOCAL_NETWORK` (declared with `targetSdk 37`) | Detect RFC1918 / link-local / `.local` targets (excluding `127.0.0.1` loopback); request `ACCESS_LOCAL_NETWORK` at runtime on API 37+ before starting `LanDiagnosticsServer` or contacting LAN MCP/SearXNG | Surface clear in-app rationale & Settings link instead of raw `EPERM`/`ECONNABORTED`; keep `127.0.0.1` Termux MCP working without the permission |
| Spoken output & background audio | API 35 / API 37 | `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Ensure `ModelRuntimeService` holds active WIU FGS before background audio focus/playback/volume calls; handle `AUDIOFOCUS_REQUEST_FAILED` cleanly | Degrade to silent text output with status message; never assume audio focus succeeded |
| Camera vision capture | API 26+ | `CAMERA` (`required="false"`) | Runtime prompt before `CameraCaptureActivity`; explicit `FLAG_GRANT_READ_URI_PERMISSION` / `FLAG_GRANT_WRITE_URI_PERMISSION` on any external URI intents | Offer gallery/document picker fallback |

### 3. Staged `compileSdk` / `targetSdk` upgrade path (35 → 36 → 37)
- **Stage A (stay on `compileSdk 35` / `targetSdk 35` — ship immediately):** Fix 16 KB ELF + ZIP alignment, `useLegacyPackaging false`, `onnxruntime-android` upgrade, v2+v3 signing, microphone `required="false"`, `AndroidPermissionGate`, `CrashLog` `MemoryLimiter:AnonSwap` parsing, `AndroidContactsGateway` pre-query permission check, and remove deprecated `setStatusBarColor`/`setNavigationBarColor` on API 35+. This immediately unblocks Android 15, 16, and 17 installation and stability.
- **Stage B (upgrade to `compileSdk 36` / `targetSdk 36`):** Adopt `OnBackInvokedCallback` / Compose `BackHandler` for predictive back across all activities; audit `JobScheduler` quotas & `WorkInfo.getStopReason()`; verify large-screen (`sw >= 600dp`) adaptive layouts across Home, Chat, Settings, and History; adopt `android:intentMatchingFlags="enforceIntentFilter"` where compatible.
- **Stage C (upgrade to `compileSdk 37` / `targetSdk 37`):** Declare and gate `ACCESS_LOCAL_NETWORK` in `AndroidPermissionGate` for `LanDiagnosticsServer` and LAN MCP/SearXNG; verify background audio hardening (WIU FGS) and `MemoryLimiter` telemetry; remove `android:usesCleartextTraffic="false"` (relying solely on `network_security_config.xml`); declare `<uses-feature android:name="android.hardware.neural_processing_unit" android:required="false" />` if spike `#164` adds direct NPU offload.

### 4. Automated CI APK compatibility gate & modern emulator coverage
- Add `.github/scripts/assert-apk-android-compat.py` (with `--self-test`) to both `build-apk.yml` and `release.yml` to fail any build that ships:
  - Any `.so` with ELF `PT_LOAD` `p_align < 16384`,
  - Compressed `.so` entries or ZIP offsets not divisible by `16384`,
  - `android:extractNativeLibs="true"`,
  - Missing v2 or v3 signature on signed release builds,
  - Missing explicit `android:exported` on any component with an intent-filter,
  - Implied required `android.hardware.microphone` or `android.hardware.camera` feature.
- Expand CI emulator testing beyond API 30: keep API 30 for backward-compatibility smoke, and add an **API 35+ (16 KB page-size system image)** emulator lane to verify install, native library load, runtime permission gates, and edge-to-edge layout on modern Android.

### 5. Documented OEM sideloading diagnostics (Xiaomi HyperOS & Android 15–17)
- Maintain [`docs/ANDROID_COMPATIBILITY.md`](../ANDROID_COMPATIBILITY.md) with concrete troubleshooting steps for Xiaomi HyperOS (Play Protect / Security scan, USB install toggles, Restricted Settings, Android 17 Unverified Developer flow, signature conflict resolution) and a one-line `adb install` / `getconf PAGESIZE` diagnostic recipe so install failures on tester phones always yield an actionable error code (`INSTALL_FAILED_*` or `INSTALL_PARSE_FAILED_*`).

## Consequences

- **Positive:**
  - Eliminates 16 KB page-size native library incompatibility on Android 15–17 flagships (including Xiaomi 15/17 and Pixel 9/10+ devices) and future-proofs against Play / OEM 16 KB mandates.
  - Prevents regressions via an automated binary compatibility gate in CI before any release tag is created.
  - Centralizes SDK-versioned permission logic so Android 13–17 behavioral changes are enforced in one tested place rather than duplicated across activities and services.
  - Prepares Hanami cleanly for Android 16 predictive back and Android 17 `ACCESS_LOCAL_NETWORK` before bumping `targetSdk`.
- **Trade-offs / Risks:**
  - Switching `useLegacyPackaging false` increases downloadable APK size slightly because `.so` files are stored uncompressed in the APK, though on-device installed storage footprint **decreases** (no second extracted copy in `/data/app/.../lib/`). Release notes and size assertions must reflect uncompressed `.so` packaging.
  - Upgrading `onnxruntime-android` requires verifying tiny-voca TTS audio parity on ARM64 and `x86_64` smoke tests.
  - Some sideloading blocks on Xiaomi HyperOS or Android 17 (e.g., Play Protect / Google Android Developer Verification) are OS/OEM policy controls outside the APK itself and require clear user installation docs (and eventually registered developer verification or F-Droid/IzzyOnDroid channels).

## Alternatives considered

- **Keep `useLegacyPackaging true` and rely on Android 16's 16 KB compatibility mode (`android:pageSizeCompat`).** Rejected. Compatibility mode displays a warning dialog on Android 16+, degrades performance, does not satisfy 16 KB native alignment mandates, and breaks on strict 16 KB-only configurations or Android 17+ enforcement.
- **Jump straight to `targetSdk 37` in a single PR.** Rejected. Jumping two API levels at once mixes native 16 KB packaging fixes with predictive back (API 36) and mandatory `ACCESS_LOCAL_NETWORK` enforcement (API 37), making regressions harder to isolate. A staged 35 (hardened) → 36 → 37 rollout delivers immediate install fixes while gating each targetSdk jump with tests.
- **Remove sensitive permissions from the manifest to appease OEM scanners.** Rejected. Voice assistant, calendar, contacts, notification summary, and local diagnostics are core opt-in Hanami features guarded by explicit runtime consent and taint-aware confirmation ([ADR 0014](0014-calendar-tools-picked-calendar.md), [ADR 0018](0018-notification-list-opt-in.md), [ADR 0025](0025-taint-aware-tool-confirmation.md)). Making hardware features `required="false"` and keeping all permissions strictly opt-in at runtime is the right balance.
