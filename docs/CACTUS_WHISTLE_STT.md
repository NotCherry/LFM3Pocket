# Cactus Whistle STT (optional, on-device)

Issue: [#188](https://github.com/NotCherry/LFM3Pocket/issues/188)

Decision: [ADR 0021](adr/0021-optional-cactus-whistle-stt.md).

LFM3 Pocket can use **Cactus Whistle** as an optional, selectable on-device speech-to-text (STT) input backend. It is an **opt-in alternative** to the existing LFM3-Omni audio-input path and does **not** replace the Omni default.

## What it does

- **Input mode setting:** users can choose **LFM3-Omni audio** (default) or **Cactus Whistle STT** in Settings.
- **Whistle path:** captured microphone audio (16 kHz mono PCM16 WAV, same capture lifecycle as existing voice input) is transcribed locally by Cactus Whistle. The recognized transcript is passed into the **existing text prompt/inference pipeline** as typed text, preserving the current tool authorization and confirmation safeguards.
- **Omni path:** unchanged. Switching providers does not break the existing Omni voice path.
- **Safety:** transcription failures, empty/silent input, permission denial, initialization/runtime/model errors, and cancellation are surfaced without triggering an LLM action. No transcript or audio is uploaded; no cloud fallback is introduced.

## Upstream components

- Model: [Cactus-Compute/whistle](https://huggingface.co/Cactus-Compute/whistle) — `whistle.cact` (~16.9 MB), Apache-2.0, 7 languages advertised upstream (English, German, French, Spanish, Italian, Dutch, Polish). **Initial app configuration is English-only** unless/until product scope expands and UX is updated.
- Runtime/API: [cactus-compute/cactus](https://github.com/cactus-compute/cactus), C API in published Android artifacts (see `cactus.h`).
- Android native artifacts: pinned in `vendor/CACTUS_RELEASE` (currently **4.1.4**) from [Cactus-Compute/cactus releases](https://huggingface.co/Cactus-Compute/cactus/tree/main/releases). `android-arm64` provides `cactus.h` and `libcactus.a`; SHA-256 pins live in `vendor/CACTUS_ANDROID_ARM64.sha256`.

## Licensing

- **Whistle model (`whistle.cact`):** Apache-2.0 (upstream model card/repo). Not redistributed in this repository or APK by default.
- **Cactus runtime/binaries/headers:** Apache-2.0 as published upstream. Do not bundle large or unverified binaries in this repository; pin versions and verify checksums for reproducible builds.

## Download / packaging strategy

- The app does **not** bundle Whistle **weights** in the APK or the repository.
- **Runtime (required for phone ARM64 releases):** `scripts/fetch-cactus.sh` downloads the pinned `android-arm64` artifacts into `third_party/cactus/arm64-v8a/` (gitignored) and verifies SHA-256. CMake links `libcactus.a` into `liblfm3pocket.so` when those files are present. Release and "Build ARM64 debug APK" CI set `HANAMI_REQUIRE_CACTUS=ON` so a missing runtime fails the build, and `.github/scripts/assert-cactus-runtime-in-apk.py` checks that `cactus_load` / `cactus_transcribe` are present in the packaged APK.
- In-app download (explicit/user-initiated): **Settings → Speech → Speech input → Download Whistle model** fetches `whistle.cact` from the pinned upstream Hugging Face revision and verifies its SHA-256 before storing it in app-private storage (`files/cactus/`). The transfer runs via `ModelDownloadManager` (user-initiated job on API 34+, foreground WorkManager below) with shade progress, cancel, free-space and metered-network checks. No audio or transcript is uploaded and there is no cloud fallback for transcription.
- Manual model: you can still obtain a verified `whistle.cact` yourself and use **Select Whistle model** to copy it into app-private storage (`files/cactus/`).
- Pinned model revision: [`Cactus-Compute/whistle@88cb00eeb8928ddbcd410b491584f938ba030b07`](https://huggingface.co/Cactus-Compute/whistle/tree/88cb00eeb8928ddbcd410b491584f938ba030b07), file `whistle.cact`, SHA-256 `b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb` (see `CactusWhistleModelManifest`).
- Smoke/x86_64 builds may omit the runtime (upstream does not publish that ABI here). Without arm64 artifacts the JNI compiles as a stub and reports "Cactus runtime is not available in this build"; Omni audio remains usable.

## Storage footprint

- `whistle.cact`: ~16.9 MB (pinned revision above).
- Runtime/library footprint depends on the pinned Cactus Android artifact and ABI.

## Supported ABIs / build integration (feasibility)

- Upstream publishes per-ABI Android artifacts (e.g. `android-arm64`, and other targets in the release tree). Check the pinned release for the exact set and requirements.
- JNI adapter: `app/src/main/cpp/cactus_whistle_jni.cpp` bridges `CactusWhistleBridge` (Java) to the Cactus C API (`cactus_load`, `cactus_init`, `cactus_transcribe`, etc.). Audio is provided as 16 kHz mono float PCM samples in [-1, 1], matching the captured WAV format used by the app.
- CMake auto-enables the runtime when `third_party/cactus/<abi>/cactus.h` and `libcactus.a` are present (also accepts `android-arm64` for `arm64-v8a`), or when `CACTUS_WHISTLE_DIR` points to a directory containing them. Without artifacts the JNI compiles as a stub and reports unavailable at runtime.
- **Reproducible build note:** runtime version is pinned in `vendor/CACTUS_RELEASE` with SHA-256 in `vendor/CACTUS_ANDROID_ARM64.sha256`. Model pin/checksums are in `CactusWhistleModelManifest`. Do not commit the downloaded binaries (`third_party/` is gitignored).

## Disable / remove

- Set **Speech input** back to **LFM3-Omni audio** to disable Whistle transcription.
- Use **Remove Whistle model** in Settings to clear the selected local model path and remove the in-app downloaded Whistle model copy; user-provided files in app-private storage can also be removed by clearing app data or deleting the file as needed.
- Uninstalling the app removes app-private model copies.

## Limitations / gaps

- Transcription quality/latency/memory depend on the pinned Cactus runtime, device ABI/CPU, and model. Measure on a supported physical Android device (see acceptance criteria / device smoke test).
- The runtime is stateful/global in the C API; the JNI bridge serializes transcription calls. Confirm clip transcription in voice-confirm flow uses the STT provider when Whistle mode is selected.
- Full native/runtime integration requires supplying verified upstream Android artifacts for the target ABI(s); this repository does not vendor them.
