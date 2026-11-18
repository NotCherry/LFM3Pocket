# Cactus Whistle STT (optional, on-device)

Issue: [#188](https://github.com/NotCherry/LFM3Pocket/issues/188)

LFM3 Pocket can use **Cactus Whistle** as an optional, selectable on-device speech-to-text (STT) input backend. It is an **opt-in alternative** to the existing LFM3-Omni audio-input path and does **not** replace the Omni default.

## What it does

- **Input mode setting:** users can choose **LFM3-Omni audio** (default) or **Cactus Whistle STT** in Settings.
- **Whistle path:** captured microphone audio (16 kHz mono PCM16 WAV, same capture lifecycle as existing voice input) is transcribed locally by Cactus Whistle. The recognized transcript is passed into the **existing text prompt/inference pipeline** as typed text, preserving the current tool authorization and confirmation safeguards.
- **Omni path:** unchanged. Switching providers does not break the existing Omni voice path.
- **Safety:** transcription failures, empty/silent input, permission denial, initialization/runtime/model errors, and cancellation are surfaced without triggering an LLM action. No transcript or audio is uploaded; no cloud fallback is introduced.

## Upstream components

- Model: [Cactus-Compute/whistle](https://huggingface.co/Cactus-Compute/whistle) — `whistle.cact` (~16.9 MB), Apache-2.0, 7 languages advertised upstream (English, German, French, Spanish, Italian, Dutch, Polish). **Initial app configuration is English-only** unless/until product scope expands and UX is updated.
- Runtime/API: [cactus-compute/cactus](https://github.com/cactus-compute/cactus), C API in published Android artifacts (see `cactus.h`).
- Android native artifacts (release example): [Cactus-Compute/cactus releases/4.0.0](https://huggingface.co/Cactus-Compute/cactus/tree/main/releases/4.0.0) — `android-arm64` provides `cactus.h` and `libcactus.a` (and other ABIs/folders as published). Verify the exact release, checksums, and supported ABIs before distributing a build.

## Licensing

- **Whistle model (`whistle.cact`):** Apache-2.0 (upstream model card/repo). Not redistributed in this repository or APK by default.
- **Cactus runtime/binaries/headers:** Apache-2.0 as published upstream. Do not bundle large or unverified binaries in this repository; pin versions and verify checksums for reproducible builds.

## Download / packaging strategy

- The app does **not** bundle Whistle weights or Cactus runtime binaries.
- To use Whistle STT: obtain a verified `whistle.cact` and select it in **Settings → Speech → Speech input → Select Whistle model**. The selected file is copied into app-private storage (`files/cactus/`).
- A build must include the Cactus native runtime for the target Android ABI for transcription to run. Without it, the provider reports "Cactus runtime is not available in this build" and the user can keep using the default LFM3-Omni audio path.

## Storage footprint

- `whistle.cact`: ~16.9 MB (per upstream model card; verify for the pinned revision you ship).
- Runtime/library footprint depends on the pinned Cactus Android artifact and ABI.

## Supported ABIs / build integration (feasibility)

- Upstream publishes per-ABI Android artifacts (e.g. `android-arm64`, and other targets in the release tree). Check the pinned release for the exact set and requirements.
- JNI adapter: `app/src/main/cpp/cactus_whistle_jni.cpp` bridges `CactusWhistleBridge` (Java) to the Cactus C API (`cactus_load`, `cactus_init`, `cactus_transcribe`, etc.). Audio is provided as 16 kHz mono float PCM samples in [-1, 1], matching the captured WAV format used by the app.
- CMake auto-enables the runtime when `third_party/cactus/<abi>/cactus.h` and `libcactus.a` (or `android-<abi>/...`) are present, or when `CACTUS_WHISTLE_DIR` points to a directory containing them. Without artifacts the JNI compiles as a stub and reports unavailable at runtime.
- **Reproducible build note:** pin the upstream runtime/model revision and record SHA-256 checksums for any downloaded artifacts used in CI or release builds. Do not commit unverified binaries.

## Disable / remove

- Set **Speech input** back to **LFM3-Omni audio** to disable Whistle transcription.
- Use **Remove Whistle model** in Settings to clear the selected local model path; delete copied files from app-private storage by clearing app data or removing the model selection/file as needed.
- Uninstalling the app removes app-private model copies.

## Limitations / gaps

- Transcription quality/latency/memory depend on the pinned Cactus runtime, device ABI/CPU, and model. Measure on a supported physical Android device (see acceptance criteria / device smoke test).
- The runtime is stateful/global in the C API; the JNI bridge serializes transcription calls. Confirm clip transcription in voice-confirm flow uses the STT provider when Whistle mode is selected.
- Full native/runtime integration requires supplying verified upstream Android artifacts for the target ABI(s); this repository does not vendor them.
