# Verification plan

## GitHub Actions

- Build the `phone` flavor for `arm64-v8a` and upload `LFM3Pocket-arm64-debug`.
- Build the `smoke` flavor for `x86_64`, launch an Android 35 emulator, and run `GenerationSmokeTest` against a small GGUF fetched for CI.
- Assert that native inference streams text, Stop completes the first generation, and a retry generates output.
- Compile the `mtmd` multimodal library along with the JNI bridge.

## Device test (Android ARM64 and official LFM3 models)

1. Import a text GGUF and verify load, Polish text output, Stop, retry, and saved-model reload.
2. Import the official Omni GGUF and matching projector; ask a question about a gallery photo and a camera photo.
3. Record a short Polish voice question and verify the local Omni model answers from its audio content.
4. Configure an HTTPS SearXNG endpoint; verify search citations open, then disable search and verify offline chat.
5. Deny microphone permission and verify typed/photo chat still works.
6. Repeat import, generation, Stop, and retry under low-memory conditions; measure heat and battery use.

CI's compact GGUF checks the JNI and generation lifecycle, not LFM3 quality or phone performance. Official model weights are not bundled in the APK.

