package com.notcherry.lfm3pocket;

/** Bounds and voice selection for local Omni speech generation. */
public final class SpeechOptions {
    public static final SpeechOptions DEFAULT = new SpeechOptions("en", "female", 512);
    public final String language;
    public final String voice;
    public final int maxFrames;

    public SpeechOptions(String language, String voice, int maxFrames) {
        if (language == null || language.trim().isEmpty()) throw new IllegalArgumentException("language");
        if (voice == null || voice.trim().isEmpty()) throw new IllegalArgumentException("voice");
        if (maxFrames < 1 || maxFrames > 4096) throw new IllegalArgumentException("maxFrames");
        this.language = language.trim();
        this.voice = voice.trim();
        this.maxFrames = maxFrames;
    }
}
