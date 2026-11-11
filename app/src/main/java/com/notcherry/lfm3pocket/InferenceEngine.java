package com.notcherry.lfm3pocket;

/**
 * App-facing model contract. Test implementations require no GGUF or JNI.
 * All implementations must deliver stream callbacks in token order.
 */
public interface InferenceEngine {
    interface TokenSink {
        void onToken(String piece);
    }

    default void configure(InferenceSettings settings) {}

    long loadModel(String modelPath, String projectorPath);
    void unloadModel(long handle);
    String generate(long handle, String prompt, String mediaPath, int maxTokens, TokenSink sink);
    void stop();
    void resetStop();
}
