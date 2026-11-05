# Verification plan

## GitHub Actions

- Build the `phone` flavor for `arm64-v8a` and upload `LFM3Pocket-arm64-debug`.
- Build the `smoke` flavor and its instrumentation APK for `x86_64`.
- Compile the `mtmd` multimodal library along with the JNI bridge.
- **Run** `GenerationSmokeTest` on a headless, KVM-accelerated Android API 30 `google_apis` x86_64 emulator via `reactivecircus/android-emulator-runner@v2`.
- Fail the PR build on a failed test assertion, native crash, failed boot, or test timeout; never treat APK assembly as a passing instrumentation test.
- Always upload instrumentation XML/HTML and emulator logcat (artifact: `LFM3Pocket-android-jni-smoke-results`) for diagnosis.

Run locally on a booted x86_64 emulator with `bash .github/scripts/run-jni-smoke.sh` (requires the Android SDK, Gradle 8.13, the llama.cpp checkout and native toolchain described in README). The script invokes `gradle :app:connectedSmokeDebugAndroidTest`, which installs the app and test APK and exercises load, token streaming, cancellation and retry through JNI.

**Deterministic smoke fixture (#6):** CI downloads the compact `stories15M-q4_0.gguf` on the **host** from pinned Hugging Face revision `499bc8821c6b12b4e53c5bffcb21ec206f212d81`, verifies SHA256 `66967fbece6dbe97886593fdbb73589584927e29119ec31f08090732d1861739`, caches it, and packages it as an `androidTest` asset. The emulator only reads the packaged asset; it never downloads a model.

To provision locally before building/running the instrumentation APK:

```bash
bash .github/scripts/provision-smoke-model.sh
bash .github/scripts/provision-smoke-model.sh --verify
gradle :app:connectedSmokeDebugAndroidTest --no-daemon
```

Corrupted cached fixtures fail immediately; to intentionally verify rejection, use `bash .github/scripts/provision-smoke-model.sh --verify /path/to/bad.gguf`. The downloaded GGUF is intentionally gitignored. This fixture proves the JNI execution lifecycle, **not** production LFM3-Omni correctness. Real ARM64 hardware with official Omni weights remains the separate product acceptance gate.

**Emulator caveat:** CI previously encountered Android boot instability. The new workflow uses a managed emulator runner with KVM and uploads logs on failure; actual CI success must be checked on the PR rather than assumed.

## Device test (Android ARM64 and official LFM3 models)

1. Import a text GGUF and verify load, Polish text output, Stop, retry, and saved-model reload.
2. Import the official Omni GGUF and matching projector; ask a question about a gallery photo and a camera photo.
3. Record a short Polish voice question and verify the local Omni model answers from its audio content.
4. Configure an HTTPS SearXNG endpoint; verify search citations open, then disable search and verify offline chat.
5. Deny microphone permission and verify typed/photo chat still works.
6. Repeat import, generation, Stop, and retry under low-memory conditions; measure heat and battery use.

CI's compact GGUF checks the JNI and generation lifecycle, not LFM3 quality or phone performance. Official model weights are not bundled in the APK.

