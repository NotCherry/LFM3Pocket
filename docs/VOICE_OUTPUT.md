# Local speech generation

Spoken responses use the loaded LFM3-Omni GGUF and matching multimodal projector through llama.cpp `mtmd` audio generation (`mtmd_helper_gen_audio_*`). There is no cloud TTS and no Android `TextToSpeech` fallback.

The app builds an LFM chat prompt (`SpeechPrompt.tts`) that asks the model to perform TTS with a language and voice. JNI streams PCM16 little-endian mono frames through `InferenceEngine.AudioSink` after announcing sample rate (typically 24 kHz for LFM3). Cancellation uses the same model-scoped stop flag as text generation. Models or projectors without `gen_audio` fail with a clear `"Speech generation is not available on this model"` status. The compact Stories15M smoke fixture has no speech generator.

`SpeechGenerationContractTest` covers the buffer contract with a fake engine (non-empty PCM16 mono at 24 kHz, unsupported rejection, WAV wrapping). Audible playback and Stop belong to V05; the full voice journey E2E belongs to V06. Real Omni speech quality on physical ARM64 remains an acceptance gate.
