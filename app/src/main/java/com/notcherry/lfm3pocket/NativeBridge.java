package com.notcherry.lfm3pocket;

public final class NativeBridge {
    static {
        System.loadLibrary("lfm3pocket");
    }

    private NativeBridge() {}

    public interface TokenSink {
        void onToken(String piece);
    }

    public interface AudioSink {
        void onAudioFormat(int sampleRateHz, int channelCount, int bitsPerSample);
        void onAudioPcm(byte[] interleavedLittleEndianPcm);
        void onAudioComplete();
    }

    public static native long loadModel(String privatePath, String privateMmprojPath);
    /** {@code true} when the loaded projector exposes an audio input path ({@code mtmd_support_audio}). */
    public static native boolean modelSupportsAudioIn(long handle);
    public static native void unloadModel(long handle);
    /** Drop the persistent text KV/hybrid session; weights stay loaded. */
    public static native void releaseSession(long handle);
    /** Compile-time + runtime build summary for Settings / diagnostics copy. */
    public static native String nativeBuildInfo();
    /**
     * llama-bench style prompt-processing (pp) and text-generation (tg) tok/s.
     * Returns a multi-line English report, or an error string.
     */
    public static native String runBenchmarkUtf8(long handle, int ppTokens, int tgTokens,
                                                 int contextTokens, int threads);

    public static String generate(long handle, String prompt, String mediaPath,
                                  int maxTokens, TokenSink sink) {
        return generateWithMedia(handle, prompt, mediaPath == null ? new String[0] : new String[]{mediaPath},
                maxTokens, sink);
    }

    /** Legacy overload — prefer {@link #generateWithMediaAndSettings}. */
    public static String generateWithMedia(long handle, String prompt, String[] mediaPaths,
                                           int maxTokens, TokenSink sink) {
        return generateWithMediaAndSettings(handle, prompt, mediaPaths, InferenceSettings.DEFAULT, sink);
    }

    public static String generateWithSettings(long handle, String prompt, String mediaPath,
                                              InferenceSettings settings, TokenSink sink) {
        return generateWithMediaAndSettings(handle, prompt,
                mediaPath == null ? new String[0] : new String[]{mediaPath}, settings, sink);
    }

    public static String generateWithMediaAndSettings(long handle, String prompt, String[] mediaPaths,
                                                      InferenceSettings settings, TokenSink sink) {
        if (prompt == null || sink == null) return "Missing prompt or callback";
        InferenceSettings limits = settings == null ? InferenceSettings.DEFAULT : settings;
        return generateUtf8(handle, PromptEncoding.encode(prompt), mediaPaths, limits.maxTokens,
                limits.contextTokens, limits.threads, sink);
    }

    public static String generateSpeech(long handle, String prompt, SpeechOptions options, AudioSink sink) {
        return generateSpeech(handle, prompt, options, InferenceSettings.DEFAULT, sink);
    }

    public static String generateSpeech(long handle, String prompt, SpeechOptions options,
                                        InferenceSettings settings, AudioSink sink) {
        if (prompt == null || sink == null) return "Missing prompt or audio callback";
        SpeechOptions selected = options == null ? SpeechOptions.DEFAULT : options;
        InferenceSettings limits = settings == null ? InferenceSettings.DEFAULT : settings;
        return generateSpeechUtf8(handle, PromptEncoding.encode(prompt), selected.language, selected.voice,
                selected.maxFrames, limits.contextTokens, limits.threads, sink);
    }

    public static String runBenchmark(long handle, InferenceSettings settings) {
        InferenceSettings limits = settings == null ? InferenceSettings.DEFAULT : settings;
        int pp = Math.min(512, Math.max(64, limits.contextTokens / 8));
        int tg = Math.min(128, Math.max(16, limits.maxTokens / 4));
        if (pp + tg + 8 >= limits.contextTokens) {
            pp = Math.max(64, limits.contextTokens / 4);
            tg = Math.max(16, Math.min(64, limits.contextTokens / 8));
        }
        return runBenchmarkUtf8(handle, pp, tg, limits.contextTokens, limits.threads);
    }

    private static native String generateUtf8(long handle, byte[] prompt, String[] mediaPaths,
                                               int maxTokens, int contextTokens, int threads, TokenSink sink);
    private static native String generateSpeechUtf8(long handle, byte[] prompt, String language, String voice,
                                                    int maxFrames, int contextTokens, int threads, AudioSink sink);
    public static native void requestStop(long handle);
    public static native void resetModelStop(long handle);
    public static native void stop();
    public static native void resetStop();
}
