package com.notcherry.lfm3pocket;

/** Voice-action capture limits (tighter than generic chat recording). */
public final class VoiceListenLimits {
    public static final int SAMPLE_RATE_HZ = 16_000;
    /** Shade Talk automatic stop (~8 s). */
    public static final int TALK_TIMEOUT_MS = 8_000;
    /** PTT and confirm clips cap (~30 s at 16 kHz). */
    public static final int MAX_ACTION_SECONDS = 30;
    public static final int MAX_ACTION_SAMPLES = SAMPLE_RATE_HZ * MAX_ACTION_SECONDS;
    /** Confirm listen window (~2 s target cap). */
    public static final int MAX_CONFIRM_SECONDS = 3;

    private VoiceListenLimits() {}
}