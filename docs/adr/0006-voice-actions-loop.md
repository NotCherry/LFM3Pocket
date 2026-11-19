# ADR 0006: Voice actions loop (Omni audio-in, mic FGS, spoken confirm)

## Status

Accepted (Voice V1). Implemented and merged in [#180](https://github.com/NotCherry/LFM3Pocket/pull/180) (2026-11-17). Epic tracking continues in [#167](https://github.com/NotCherry/LFM3Pocket/issues/167); see [`docs/plans/voice-actions-issues.md`](../plans/voice-actions-issues.md) for the 2026-11-18 audit snapshot.

## Context

Hands-free voice actions must reuse the existing `specialUse` model residency, `ToolCoordinator` consent model, and LFM3-Omni multimodal inference. Speech input must not use Android `SpeechRecognizer`, whisper.cpp, or Vosk. Screen-off capture needs a short `microphone` foreground-service type only while listening.

## Decision

1. **Capture:** `VoiceListenSession` wraps `RecordingController` with a tighter 16 kHz PCM cap and optional silence trim. Shade **Talk** starts a timed listen via `ModelRuntimeService` `PendingIntent`; in-app hold-to-talk delegates to `VoicePttCoordinator` (not `MainActivity` logic).
2. **Inference:** `VoiceActionRuntime` runs one `generateWithMedia` pass per command using `VoiceToolPromptCard` (no ASR-then-reply double pass). Text-only pairs disable Talk via `VoiceAudioCapability`.
3. **Speech out:** `VoiceSpokenOutput` prefers Omni `generateSpeech`; otherwise labelled system TTS (`LabeledSystemTts`). Confirm read-backs speak exact `ToolCoordinator.describe` text.
4. **Confirm:** Short confirm listen → ASR-style Omni pass → `VoiceConfirmLexicon` only (tak/nie + yes/no). Ambiguous → reject.
5. **FGS:** `RuntimeNotice.microphoneCapture` drives `FOREGROUND_SERVICE_TYPE_MICROPHONE` alongside existing `specialUse` / `mediaPlayback`.

## Consequences

- Notification actions add **Talk**; manifest declares microphone FGS type.
- `MainActivity` stays thin: touch forwarding only when voice actions are enabled.
- Device checklist and ARM64 Omni evidence remain manual ([issue #158](https://github.com/NotCherry/LFM3Pocket/issues/158), [`docs/VOICE_LOOP_CHECKLIST.md`](../VOICE_LOOP_CHECKLIST.md)); as of 2026-11-18 no arm64 VL-01–VL-06 run is recorded in-repo.

## References

- Plan: [voice-actions.md](../plans/voice-actions.md) (PR #149 branch until merged)
- Epic: [#167](https://github.com/NotCherry/LFM3Pocket/issues/167)