# Real Omni runs on the local Android emulator

These are real production-JNI runs with the published LFM3-Omni model/projector on an API 30 x86_64 emulator. They establish that this emulator build can validate, load and invoke the real text, image and audio paths. They do **not** qualify ARM64 devices, production phone performance, the Android picker/recorder UI, airplane-mode operation, speech generation, or combined audio+image input. Issue #13 stays open and AT-04 is PARTIAL.

## Reproducibility

- Emulator: `sdk_gphone_x86_64`, Android API 30, x86_64, 4 GiB configured RAM.
- Probe: `RealOmniProbeTest`, isolated `com.notcherry.lfm3pocket.devicecheck` app with the production `Lfm3PocketApplication` and native JNI. Emulator use requires explicit `-e allowEmulator true`.
- Model source: [LiquidAI/LFM3-Omni-2B-GGUF](https://huggingface.co/LiquidAI/LFM3-Omni-2B-GGUF/tree/d508b88f08a5b4286f2d2805a6a8d69a0ff44c72), revision `d508b88f08a5b4286f2d2805a6a8d69a0ff44c72`.
- Model `LFM3-Omni-2B-Q4_K_M.gguf`: 1,099,630,080 bytes; SHA-256 `9f8d30e9d4fd7a9349d586e077988229b3ca0a0e4ad730897563d1eccc9893e2`.
- Projector `mmproj-LFM3-Omni-2B-Q4_K_M.gguf`: 291,586,720 bytes; SHA-256 `0a746c0794782a16d4456b230c2a0b5888a2d94a6677211edbed7d7a4177a39a`.
- Image fixture: generated red square on the left and blue circle on the right; SHA-256 `796bc9c7f2335eafa1adcf90838d8686ec33bf9874f19d87cf7bf960592427f8`.
- Audio fixture: generated English speech, “The secret animal is a blue cat. The number is seven.”; SHA-256 `6fb91169177f97113009f31a8c664ccb3342c8eca53b61943b5fba8426c708d3`.
- Full device reports: [text](evidence/omni-emulator/text.json), [image](evidence/omni-emulator/image.json), [audio](evidence/omni-emulator/audio.json). Reports record the exact prompt, native output and phase timings. `quality` remains `REQUIRES_REVIEW`; the probe does not award quality scores.

## Observed results

| Path | Result | Validation | Load | First token | Decode |
| --- | --- | ---: | ---: | ---: | ---: |
| Text, Polish prompt | PASS: non-empty Polish answer about local processing | 4,997 ms | 674 ms | 2,143 ms | 4,846 ms |
| Image, synthetic two-shape fixture | PASS on this fixture: identified red square left and blue circle right | 5,043 ms | 611 ms | 13,663 ms | 21,294 ms |
| Audio, synthetic English WAV | PASS on this fixture: “The secret animal is a blue cat, the number is seven.” | 5,109 ms | 663 ms | 3,930 ms | 4,996 ms |

All three instrumentation runs completed `OK (1 test)` with `error: null` and non-empty streamed output. These timings are from a virtual x86_64 CPU and must not be compared to a physical phone as release performance.

## Remaining acceptance work

- Repeat the probes on physical ARM64 hardware with the exact model/projector pair, or retain #13 as blocked on that hardware requirement.
- Exercise gallery import, camera capture, microphone recording, cancellation, offline use and sustained-load behavior through the app UI on supported phones.
- Verify Polish speech, spoken model output, combined image+audio, and quality over a broader set of fixtures. This emulator evidence only covers one synthetic image and one synthetic English recording.
