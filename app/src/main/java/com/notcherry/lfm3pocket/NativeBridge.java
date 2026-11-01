package com.notcherry.lfm3pocket;

public final class NativeBridge {
    static {
        System.loadLibrary("lfm3pocket");
    }

    private NativeBridge() {}

    public interface TokenSink {
        void onToken(String piece);
    }

    public static native long loadModel(String privatePath);
    public static native void unloadModel(long handle);
    public static native String generate(long handle, String prompt, int maxTokens, TokenSink sink);
    public static native void stop();
}
