# Voice output lifecycle

Spoken responses are produced only after a completed local assistant turn that began with a recorded voice attachment. `InferenceEngine.synthesizeSpeech` is the Stage 1 contract: the default and native adapters return `Speech generation is not supported by this build`, while instrumentation fakes write a short PCM16 mono fixture tone so playback can be exercised without Omni speech-out.

When synthesis succeeds, `ChatSession` writes a private `spoken-*.wav` under the speech cache directory and notifies `Listener.speechReady`. `MainActivity` owns a `PlaybackController` with states IDLE, STARTING, PLAYING, STOPPING, ERROR and CLOSED. Android `AudioTrack` MODE_STREAM stays on a dedicated worker. Stop interrupts active playback; Activity destruction closes the controller and suppresses stale callbacks. Returning to the Activity never auto-replays.

`SpeechAudio` validates PCM16 mono 16 kHz WAV shape for both fixture tones and playback. `HistoryMediaCleanup` deletes orphaned `spoken-*` files after cancellation or process death. Physical ARM64 Omni speech generation, latency and audible quality remain release gates outside this no-phone slice.

PlaybackControllerTest covers open-before-play, close-before-queued-start and recoverable open failure. VoiceOutputE2eTest drives EMU-FAKE PTT → ASR/assistant → fixture playback → Stop. Native Omni speech-out is still explicitly unsupported.
