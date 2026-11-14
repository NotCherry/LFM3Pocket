# ARM64 Omni compatibility report — RMX2170

Physical qualification for F01 / AT-04. DeviceCheck package
`com.notcherry.lfm3pocket.devicecheck` with production JNI. Host weights were
`LFM3-Omni-2B-Q4_0` + `mmproj-LFM3-Omni-2B-Q8_0` copied under the probe’s
`Q4_K_M` filenames (JSON labels are filenames only).

## Device

| Field | Value |
| --- | --- |
| Model | realme RMX2170 |
| ABI | arm64-v8a |
| Android | 12 / API 31 |
| MemTotal | ~7.7 GiB |
| Transport | Wi-Fi ADB |

## Supported native pathways

| Pathway | Result | Evidence |
| --- | --- | --- |
| Text chat (Polish prompt) | PASS | `device-evidence/reports/probe-text.json` |
| Image + matching projector | PASS | `device-evidence/reports/probe-image.json` — blue square / yellow circle on red |
| Audio ASR English (SAPI) | PARTIAL | `probe-audio-en-rmx2170.json` — number OK; animal wrong |
| Audio ASR Polish (SAPI Paulina) | PASS (exact) | `probe-audio-pl-asr-rmx2170.json` |
| ASR → text answer (V02 path) | PASS | `probe-audio-pl-answer-text-rmx2170.json`; ChatSession PR #113 |
| Mismatched projector | FAIL safe | `probe-mismatch-rmx2170.json` — `Select a multimodal projector, not a language model` |
| Flashlight CameraManager | PASS | #84 closed |

## Failing / limited

| Item | Status | Notes |
| --- | --- | --- |
| Re-attach WAV after ASR | Fixed in app | Echoed/transcribed; ChatSession drops WAV after successful ASR (#113) |
| eSpeak Polish synthetic | FAIL (emulator) | Not representative; SAPI pl-PL succeeds on ARM64 |
| Human-microphone Polish | NOT_RUN | Requires operator utterance; fixtures above are OS TTS |
| True airplane-mode TX | BLOCKED on Wi-Fi ADB | Protocol requires USB or on-device handoff (`ARM64_OMNI_TEST_PROTOCOL.md`) |
| Gallery / CameraX UI import | NOT_RUN on ARM64 | Emulator E2E exists; native image path proven via probe |
| Omni speech (TTS) generation | PASS | `probe-speech-rmx2170.json` / `.wav` — after #116 TTS prompt ends with `<|audio_start|>`; 24 kHz mono PCM, `pcmBytes=38400`, `firstAudioMs=4112`, `speechMs=6832` |

## Missing / out of Stage 1 scope

- Persistent AI memory / reminders (Stage 2)
- Cloud ASR / cloud TTS fallbacks (explicitly excluded)
- Combined gallery+mic UI journey on physical hardware

## Minimal reproducers

Text / image / audio / mismatch use `RealOmniProbeTest` with `-e realOmni true`
as documented in `docs/ARM64_OMNI_TEST_PROTOCOL.md`. Full session notes:
`device-evidence/reports/arm64-session.md`.
