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
    public static native void unloadModel(long handle);
    public static String generate(long handle, String prompt, String mediaPath,
                                  int maxTokens, TokenSink sink) {
        return generateWithMedia(handle,prompt,mediaPath==null?new String[0]:new String[]{mediaPath},maxTokens,sink);
    }
    public static String generateWithMedia(long handle, String prompt, String[] mediaPaths,
                                           int maxTokens, TokenSink sink) {
        if (prompt == null || sink == null) return "Missing prompt or callback";
        return generateUtf8(handle, PromptEncoding.encode(prompt), mediaPaths, maxTokens, 4096, 4, sink);
    }
    public static String generateWithSettings(long handle,String prompt,String mediaPath,
                                              InferenceSettings settings,TokenSink sink) {
        return generateWithMediaAndSettings(handle,prompt,mediaPath==null?new String[0]:new String[]{mediaPath},settings,sink);
    }
    public static String generateWithMediaAndSettings(long handle,String prompt,String[] mediaPaths,
                                                      InferenceSettings settings,TokenSink sink) {
        if(prompt==null || sink==null) return "Missing prompt or callback";
        return generateUtf8(handle,PromptEncoding.encode(prompt),mediaPaths,settings.maxTokens,
                settings.contextTokens,settings.threads,sink);
    }
    public static String generateSpeech(long handle, String prompt, SpeechOptions options, AudioSink sink) {
        if (prompt == null || sink == null) return "Missing prompt or audio callback";
        SpeechOptions selected = options == null ? SpeechOptions.DEFAULT : options;
        return generateSpeechUtf8(handle, PromptEncoding.encode(prompt), selected.language, selected.voice,
                selected.maxFrames, 4096, 4, sink);
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

