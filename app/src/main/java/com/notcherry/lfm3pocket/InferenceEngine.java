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
    /**
     * Generates with media in prompt-marker order. Each path must correspond to one {@code <__media__>} marker.
     * Adapters that support only one attachment fail clearly when more than one path is supplied.
     */
    default String generateWithMedia(long handle, String prompt, String[] mediaPaths, int maxTokens, TokenSink sink) {
        if (mediaPaths == null || mediaPaths.length == 0)
            return generate(handle, prompt, (String) null, maxTokens, sink);
        if (mediaPaths.length > 1) return "This inference engine does not support multiple media attachments";
        return generate(handle, prompt, mediaPaths[0], maxTokens, sink);
    }
    void stop();
    void resetStop();
}
