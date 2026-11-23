# ADR 0006: Voice actions loop (Omni audio-in, mic FGS, spoken confirm)

## Status

Accepted (Voice V1). Implemented and merged in [#180](https://github.com/NotCherry/LFM3Pocket/pull/180) (2026-11-17). Confirm-fail-closed and unload ordering were tightened in [#204](https://github.com/NotCherry/LFM3Pocket/pull/204). Epic tracking continues in [#167](https://github.com/NotCherry/LFM3Pocket/issues/167); see [`docs/plans/voice-actions-issues.md`](../plans/voice-actions-issues.md) for the 2026-11-18 audit snapshot and the 2026-11-23 update.

## Context

Hands-free voice actions must reuse the existing `specialUse` model residency, `ToolCoordinator` consent model, and LFM3-Omni multimodal inference. Speech input must not use Android `SpeechRecognizer`, whisper.cpp, or Vosk. Screen-off capture needs a short `microphone` foreground-service type only while listening.

## Decision

1. **Capture:** `VoiceListenSession` wraps `RecordingController` with a tighter 16 kHz PCM cap and optional silence trim. Shade **Talk** starts a timed listen via `ModelRuntimeService` `PendingIntent`; in-app hold-to-talk delegates to `VoicePttCoordinator` (not `MainActivity` logic).
2. **Inference:** `VoiceActionRuntime` runs one `generateWithMedia` pass per command using `VoiceToolPromptCard` (no ASR-then-reply double pass). Text-only pairs disable Talk via `VoiceAudioCapability`.
3. **Speech out:** `VoiceSpokenOutput` prefers Omni `generateSpeech`; otherwise labelled system TTS (`LabeledSystemTts`). Confirm read-backs speak exact `ToolCoordinator.describe` text.
4. **Confirm:** Short confirm listen → ASR-style Omni pass → `VoiceConfirmLexicon` only (tak/nie + yes/no). Ambiguous → reject. A failed or cancelled confirm pass also rejects. Partial text from that pass is not an approval, even when it contains “yes” or “tak” ([#204](https://github.com/NotCherry/LFM3Pocket/pull/204)).
5. **FGS:** `RuntimeNotice.microphoneCapture` drives `FOREGROUND_SERVICE_TYPE_MICROPHONE` alongside existing `specialUse` / `mediaPlayback`.
6. **Unload:** Model unload cancels an in-flight listen and any pending spoken confirmation before the model is released, so a late recording cannot run inference on a retired model ([#204](https://github.com/NotCherry/LFM3Pocket/pull/204)). Policy updates that drive that unload stay on the owner thread ([ADR 0016](0016-runtime-policy-owner-thread.md)).
7. **Assistant gesture:** The system assistant entry opens Hanami’s existing chat and requests a voice session. It does not authorize tools ([#218](https://github.com/NotCherry/LFM3Pocket/pull/218)).

## Consequences

- Notification actions add **Talk**; manifest declares microphone FGS type.
- `MainActivity` stays thin: touch forwarding only when voice actions are enabled.
- Device checklist and ARM64 Omni evidence remain manual ([issue #158](https://github.com/NotCherry/LFM3Pocket/issues/158), [`docs/VOICE_LOOP_CHECKLIST.md`](../VOICE_LOOP_CHECKLIST.md)); as of 2026-11-18 no arm64 VL-01–VL-06 run is recorded in-repo.

## References

- Plan: [voice-actions.md](../plans/voice-actions.md) (merged in [#149](https://github.com/NotCherry/LFM3Pocket/pull/149))
- Epic: [#167](https://github.com/NotCherry/LFM3Pocket/issues/167)
- Later tools on the same consent path: reminders [ADR 0007](0007-voice-reminders-workmanager.md), calendar [ADR 0014](0014-calendar-tools-picked-calendar.md)