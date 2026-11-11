package com.notcherry.lfm3pocket;

public final class NativeBridge {
    static {
        System.loadLibrary("lfm3pocket");
    }

    private NativeBridge() {}

    public interface TokenSink {
        void onToken(String piece);
    }

    public static native long loadModel(String privatePath, String privateMmprojPath);
    public static native void unloadModel(long handle);
    public static String generate(long handle, String prompt, String mediaPath,
                                  int maxTokens, TokenSink sink) {
        if (prompt == null || sink == null) return "Missing prompt or callback";
        return generateUtf8(handle, PromptEncoding.encode(prompt), mediaPath, maxTokens, 4096, 4, sink);
    }
    public static String generateWithSettings(long handle,String prompt,String mediaPath,
                                              InferenceSettings settings,TokenSink sink) {
        if(prompt==null || sink==null) return "Missing prompt or callback";
        return generateUtf8(handle,PromptEncoding.encode(prompt),mediaPath,settings.maxTokens,
                settings.contextTokens,settings.threads,sink);
    }
    private static native String generateUtf8(long handle, byte[] prompt, String mediaPath,
                                               int maxTokens, int contextTokens, int threads, TokenSink sink);
    public static native void requestStop(long handle);
    public static native void resetModelStop(long handle);
    public static native void stop();
    public static native void resetStop();
}

