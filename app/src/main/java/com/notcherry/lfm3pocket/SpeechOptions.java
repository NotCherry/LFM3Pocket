package com.notcherry.lfm3pocket;

/** Bounds and voice selection for local Omni speech generation. */
public final class SpeechOptions {
    public static final int MAX_FRAMES_LIMIT = 4096;
    public static final SpeechOptions DEFAULT = new SpeechOptions("en", "female", 1024);
    public final String language;
    public final String voice;
    public final int maxFrames;

    public SpeechOptions(String language, String voice, int maxFrames) {
        if (language == null || language.trim().isEmpty()) throw new IllegalArgumentException("language");
        if (voice == null || voice.trim().isEmpty()) throw new IllegalArgumentException("voice");
        if (maxFrames < 1 || maxFrames > MAX_FRAMES_LIMIT) throw new IllegalArgumentException("maxFrames");
        this.language = language.trim();
        this.voice = voice.trim();
        this.maxFrames = maxFrames;
    }

    /**
     * Speech frames are not chat tokens. Budget scales with spoken text length so long replies
     * are less likely to cut off mid-sentence (hard cap {@link #MAX_FRAMES_LIMIT}).
     */
    public static int framesForText(String text) {
        int chars = text == null ? 0 : text.trim().length();
        int frames = 1024 + Math.max(0, chars) * 2;
        return Math.max(512, Math.min(MAX_FRAMES_LIMIT, frames));
    }
}
