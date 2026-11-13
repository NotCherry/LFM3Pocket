# Stage 1 release documentation

## Reproducible APK build

1. Install JDK 17, Android SDK/NDK and Gradle 8.13.
2. From a clean checkout: `git clone --branch v0.8.0 --depth 1 https://github.com/ggml-org/llama.cpp.git third_party/llama.cpp`
3. Build the phone APK: `gradle :app:assemblePhoneDebug`
4. GitHub Actions builds the ARM64 APK, runs x86_64 emulator instrumentation (JNI smoke + EMU-FAKE UI), and uploads the APK artifact. Model weights are never bundled in the APK.

Pinned llama.cpp revision: `v0.8.0`. Projector and text GGUF files are imported at runtime through SAF into private app storage.

## Model import gate

Import an official quantized LFM3 GGUF. For Omni multimodal pathways, import both the matching `LFM3-Omni-2B-Q4_K_M.gguf` and `mmproj-LFM3-Omni-2B-Q4_K_M.gguf`. Metadata validation rejects role/family/dimension mismatches before acceptance. Recommended phone RAM: at least 4 GB, preferably 6+ GB.

## Local diagnostics

`DiagnosticsRecorder` stores local timing and lifecycle marks only. Rendered reports state that marks are never uploaded. No telemetry ships with Stage 1.

## Test gates (no-phone)

| Gate | Owner | Evidence tier |
| --- | --- | --- |
| Voice capture + ASR transcript | RecordingControllerTest, RecordingE2eTest, ChatSessionTest | JVM / EMU-FAKE |
| Spoken playback + Stop | PlaybackControllerTest, VoiceOutputE2eTest | JVM / EMU-FAKE |
| Combined photo + voice + follow-up | ChatSessionTest, VisionE2eTest | JVM / EMU-FAKE |
| Multi-media JNI entry | NativeBridge.generateMultiWithSettings / generateMultiUtf8 | EMU-JNI when projector present |
| Privacy / offline | ChatUiE2eTest airplane and permission denial cases | EMU-FAKE |

## Known limitations

- Native Omni speech-audio generation remains explicitly unsupported in this build; fixtures cover playback wiring only.
- Physical ARM64 Omni visual grounding, Polish ASR accuracy and audible speech quality are open release gates (see `docs/ACCEPTANCE_MATRIX.md` and `docs/ARM64_OMNI_TEST_PROTOCOL.md`).
- CI emulator evidence does not certify real-device Omni quality.
