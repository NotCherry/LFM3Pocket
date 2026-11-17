# ADR 0010 — Optional English tiny-voca TTS

- Status: Accepted
- Date: 2026-11-17
- Related: [n4ze3m/tiny-voca](https://github.com/n4ze3m/tiny-voca), voice epic [#167](https://github.com/NotCherry/LFM3Pocket/issues/167), plan PR [#149](https://github.com/NotCherry/LFM3Pocket/pull/149)

## Context

Spoken replies use on-device LFM3-Omni audio generation on the loaded model. That path is high quality, but each utterance is another multimodal decode on the main weights. The voice epic ([#167](https://github.com/NotCherry/LFM3Pocket/issues/167), plan [#149](https://github.com/NotCherry/LFM3Pocket/pull/149)) covers a broader loop (Omni audio-in, Polish confirmations, a foreground service) and is out of scope here.

[tiny-voca](https://github.com/n4ze3m/tiny-voca) is a separate **4.5M-parameter**, **~18–21 MB** English TTS model (Apache-2.0 code and weights). Upstream reports about **70× real-time** on four desktop CPU threads and ships an ONNX build for on-device use. It is **English-only** (fixed voices, 24 kHz).

Hanami needs an **opt-in, off-by-default** Speak-reply path for that model. Text that is not classified as English (for example Polish) keeps the Omni voice. English keeps Omni only while the setting is off. When the setting is on, English uses tiny-voca if the optional pack and phonemizer are ready, and the Android system voice if either is missing.

## Decision

1. **Setting** — Global, off by default, under Settings → Speech, with English and Polish copy that states **English only**.
2. **Assets** — Weights are not in the APK. The user downloads an optional pack (ONNX model plus phonemizer data, about 27 MB) from a pinned `n4ze3m/tiny-voca` revision on Hugging Face into app-private storage.
3. **Routing** — Speak reply selects a backend:
   - setting off → **Omni**;
   - setting on, and the text is not classified as English (for example Polish) → **Omni**;
   - setting on, English, pack and phonemizer ready → **tiny-voca** through a separate on-device ONNX Runtime stack;
   - setting on, English, pack or phonemizer missing → **Android system TTS** (US English), not Omni.
4. **Pins** — The pack is a pinned upstream revision. Each downloaded file is checked with SHA-256; a mismatch is rejected. The phonemizer’s voca sources are pinned to their own upstream revision.
5. **Phonemizer** — A small native library built from the permissive [voca](https://github.com/sultson/voca) / dizione sources (Apache-2.0, CMUdict-derived data) when those sources, the NDK, and Rust are available. Failures are reported to the app instead of crashing the process.
6. **Voice epic boundary** — No change to audio-in, push-to-talk, or the assistant voice-interaction path in #149 / #167. This decision only extends **Speak reply** (text out).

## Consequences

- English Speak reply can be faster on a weak phone once the pack is installed (a 4.5M model instead of another Omni speech decode). Non-English replies, including Polish, stay on Omni even when the setting is on.
- Extra optional download and a second on-device inference stack (ONNX Runtime plus a small native phonemizer).
- A build without Rust or the NDK still compiles. English then uses the system-TTS fallback.
- If tiny-voca synthesis fails or yields no audio, that English utterance falls back to the system US English voice.
- License: the app stays MIT. tiny-voca (code and weights) and dizione are Apache-2.0. ONNX Runtime is a separate dependency.

## Alternatives considered

- **Omni-only** — Rejected for this feature; an optional tiny-voca path was requested.
- **eSpeak NG phonemizer** — Would add a GPL runtime dependency. Released tiny-voca checkpoints use permissive dizione.
- **Cloud TTS** — Rejected; the product is offline-first.
