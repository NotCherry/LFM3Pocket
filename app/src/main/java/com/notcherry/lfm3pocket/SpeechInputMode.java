package com.notcherry.lfm3pocket;

/** User-selected speech input backend. Persisted as a stable wire name. */
public enum SpeechInputMode {
    /** Existing LFM3-Omni audio-in path (default). */
    OMNI_AUDIO("omni_audio"),
    /** Optional Cactus Whistle on-device STT -> text pipeline. */
    CACTUS_WHISTLE_STT("cactus_whistle_stt");

    public final String wireName;

    SpeechInputMode(String wireName) {
        this.wireName = wireName;
    }

    public static SpeechInputMode fromWire(String value) {
        if (value == null) return OMNI_AUDIO;
        for (SpeechInputMode mode : values()) {
            if (mode.wireName.equals(value) || mode.name().equalsIgnoreCase(value)) return mode;
        }
        return OMNI_AUDIO;
    }
}
