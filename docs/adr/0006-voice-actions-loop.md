# ADR 0006: Voice actions loop (Omni audio-in, mic FGS, spoken confirm)

## Status

Accepted (Voice V1). Merged in [#180](https://github.com/NotCherry/LFM3Pocket/pull/180).

## Context

Hands-free voice actions stay on the phone. They reuse the existing model-residency foreground service, the same Approve/Reject consent as the notification shade, and LFM3-Omni multimodal inference.

Speech input does not use Android's speech recognizer, whisper.cpp, Vosk, or a network speech-to-text service. A command is not transcribed and then answered in a second pass.

Listening with the screen off needs the microphone foreground-service type, and only while a listen window is open.

## Decision

1. **On-device audio in.** Each command is one short clip fed straight into a single LFM3-Omni multimodal pass (answer, or one tool proposal). Voice actions require a loaded model and a projector that can hear audio. Without that, the notification **Talk** action is omitted. If Talk is started anyway, the app speaks a clear English and Polish explanation and does not switch to another recognizer.

2. **Capture.** Clips are bounded 16 kHz mono audio, shorter than ordinary chat recording. Notification Talk and the accessibility toggle stop on their own; push-to-talk ends when the user releases. Leading and trailing silence may be trimmed. A trim that fails is ignored and the clip is still used.

3. **Entry points.** In chat, push-to-talk (press and hold). When touch exploration is on, a tap toggles a timed listen instead of a hold. On the model notification, **Talk** starts that timed listen in the residency service, including with the screen off. The chat screen only forwards the control.

4. **Service type.** The same residency service adds the microphone foreground-service type only during a listen window, then drops it. Residency itself is unchanged. The playback type is added only while audio is actually playing. A listen window holds a partial wake lock.

5. **Tools and consent.** A tool runs only when the model proposes one allowlisted action, through the same consent path as shade Approve/Reject. Voice does not execute a tool on its own. A model proposal waits for confirmation even when the action would be safe from an explicit in-app control. Starting a new listen while a confirmation is open rejects that proposal.

6. **Spoken output.** Replies and read-backs prefer on-device Omni speech. If that yields no audio, or playback cannot start, the app uses Android system speech and labels the path so the substitution is obvious. A Polish locale tries the Polish system voice, then US English. This loop does not use the optional English Speak-reply pack.

7. **Confirmation.** The app speaks the exact tool description, then a short yes-or-no cue in the user's language. A second short listen is transcribed by the same on-device model and matched only against a closed lexicon: Polish tak/nie, English yes/no, and a few fixed synonyms (confirm / cancel and the like). Empty, unrecognized, or mixed answers reject. The model does not judge the answer. The outcome is spoken in the user's language.

## Consequences

- The manifest declares the microphone foreground-service type on the residency service. The notification gains **Talk** when the loaded pair can hear audio.
- Voice-state copy ships in English and Polish (Talk, listening, the incapable-model explanation, microphone permission, and the labelled system-voice fallback).
- The chat screen stays thin. It forwards the microphone control into this loop only when voice actions are enabled; otherwise that control keeps ordinary chat recording.
- On-device evidence is manual. Record it with [VOICE_LOOP_CHECKLIST.md](../VOICE_LOOP_CHECKLIST.md), following [ARM64_OMNI_TEST_PROTOCOL.md](../ARM64_OMNI_TEST_PROTOCOL.md). Emulator runs do not close that gate.

## References

- Plan: [voice-actions.md](../plans/voice-actions.md)
- Epic: [#167](https://github.com/NotCherry/LFM3Pocket/issues/167)
