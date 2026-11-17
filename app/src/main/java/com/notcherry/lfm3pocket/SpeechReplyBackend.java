package com.notcherry.lfm3pocket;

/** Chooses how assistant replies are spoken (Omni, tiny-voca, or system TTS). */
public enum SpeechReplyBackend {
    /** LFM3-Omni local mtmd speech generation (existing default). */
    OMNI,
    /** English-only optional tiny-voca ONNX path. */
    TINY_VOCA,
    /** Android {@link android.speech.tts.TextToSpeech} fallback. */
    SYSTEM_TTS
}