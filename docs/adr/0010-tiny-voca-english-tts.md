# ADR 0010 — Optional English tiny-voca TTS

- Status: Accepted
- Date: 2026-11-17
- Related: [n4ze3m/tiny-voca](https://github.com/n4ze3m/tiny-voca), voice epic [#167](https://github.com/NotCherry/LFM3Pocket/issues/167), plan PR [#149](https://github.com/NotCherry/LFM3Pocket/pull/149)

## Context

Spoken replies today use LFM3-Omni `mtmd` audio generation on the loaded GGUF (`docs/VOICE_OUTPUT.md`). That path is high quality but ties speech latency to another multimodal decode pass on the main LLM weights. PR #149 plans a broader voice loop (Omni audio-in, Polish confirmations, FGS) without duplicating quick wins.

[tiny-voca](https://github.com/n4ze3m/tiny-voca) is a separate **4.5M-parameter**, **~18–21 MB** English TTS model (Apache-2.0 code and weights). Upstream reports **~70× real-time** on four desktop CPU threads and ships an **ONNX** build for on-device use. It is **English-only** (two fixed voices: F2/M2, 24 kHz).

Hanami needs an **opt-in, off-by-default** global setting with **Polish-aware fallback**: system `TextToSpeech` for non-English text or when the optional pack / phonemizer is missing; Omni remains the default when the setting is off.

## Decision

1. **Setting** — `RuntimePreferences.tiny_voca_english_tts` (default `false`), exposed under Settings → Speech with EN/PL copy that states **English only**.
2. **Assets** — Do not ship weights in the APK. User taps **Download tiny-voca pack** to fetch `onnx/model.onnx` plus dizione phonemizer files from `n4ze3m/tiny-voca` on Hugging Face (~27 MB total) into app private storage (`files/tiny-voca/`).
3. **Runtime** — `SpeechReplyRouter` in `ModelRuntime.speak()` (phonemizer library loaded via `ensureLoaded` before routing):
   - setting off → **Omni** (unchanged);
   - setting on + non-English (e.g. Polish) → **Omni** (unchanged for non-English);
   - setting on + English + pack + `libhanami_dizione.so` → **ONNX Runtime** synthesis;
   - setting on + English + missing pack or JNI → **system TTS (US English)** (not Omni).
4. **Pins** — Hugging Face revision `TinyVocaPackManifest.HF_REVISION` with per-file SHA-256 verification after download; voca sources pinned in `vendor/VOCA_COMMIT` (`scripts/fetch-voca.sh`).
5. **Phonemizer** — JNI wrapper around the Rust [voca](https://github.com/sultson/voca) `dizione` crate (Apache-2.0, CMUdict-derived data), built via `scripts/build-dizione-jni.sh` when voca + NDK + Rust are available. JNI returns errors to Java instead of panicking.
6. **Voice epic boundary** — No changes to audio-in, PTT, or `VoiceInteractionService` planned in #149. This ADR only extends **Speak reply** text-out.

## Consequences

- Faster English speak-reply on weak phones when the pack is installed (separate 4.5M model vs Omni TTS decode).
- Extra optional download and a second inference stack (ONNX + small native phonemizer).
- CI gains `scripts/fetch-voca.sh` and a best-effort Rust JNI build step (`buildHanamiDizioneJni`); builds without Rust/NDK still compile with fallback-only behaviour.
- License: Hanami app remains MIT; tiny-voca / dizione / ONNX Runtime / CMUdict and Rust JNI deps are documented in [`licenses/THIRD_PARTY_NOTICES.md`](../licenses/THIRD_PARTY_NOTICES.md) and README. Shipping those notices inside the APK is a follow-up.

## Alternatives considered

- **Omni-only** — Rejected for this feature; owner requested optional tiny-voca.
- **eSpeak NG phonemizer** — GPL runtime dependency; released tiny-voca checkpoints use dizione (permissive).
- **Cloud TTS** — Rejected; offline-first product.