# Local speech generation

By default, spoken responses use the loaded LFM3-Omni GGUF and matching multimodal projector through llama.cpp `mtmd` audio generation (`mtmd_helper_gen_audio_*`). There is no cloud TTS.

When **Settings → Speech → Use tiny-voca for English replies** is on, Hanami may speak **English** via the optional on-device [tiny-voca](https://github.com/n4ze3m/tiny-voca) ONNX pack (see `docs/adr/0010-tiny-voca-english-tts.md`). **Polish and other non-English replies keep the LFM3-Omni path.** If the pack or phonemizer is missing, English falls back to Android **TextToSpeech** (US English voice). The global toggle is off by default.

The app builds an LFM chat prompt (`SpeechPrompt.tts`) that asks the model to perform TTS with a language and voice. JNI streams PCM16 little-endian mono frames through `InferenceEngine.AudioSink` after announcing sample rate (typically 24 kHz for LFM3). Cancellation uses the same model-scoped stop flag as text generation. Models or projectors without `gen_audio` fail with a clear `"Speech generation is not available on this model"` status. The compact Stories15M smoke fixture has no speech generator.

`SpeechGenerationContractTest` covers the buffer contract with a fake engine (non-empty PCM16 mono at 24 kHz, unsupported rejection, WAV wrapping).

Playback uses `PlaybackController` with an injectable track (production `AudioTrack` via `AndroidSpeechTrack`) and transient audio focus (`AndroidPlaybackFocus`). **Speak reply** generates speech for the last assistant answer through the local Omni path, then plays PCM16 mono; **Stop speech** (and the chat Stop control while playing) cancels playback and releases the track/focus. `PlaybackControllerTest` covers play/stop/focus denial/write failure/close races without opening a real AudioTrack.

The full push-to-talk → transcript → spoken reply journey E2E belongs to V06. Real Omni speech quality on physical ARM64 remains an acceptance gate.
