package com.notcherry.lfm3pocket;

import java.io.File;
import java.util.Collections;
import java.util.List;

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
     * Generates with zero or more media paths in prompt-marker order.
     * Each path must correspond to one {@code <__media__>} marker.
     * Adapters that support only one attachment fail clearly when more than one path is supplied.
     */
    default String generate(long handle, String prompt, List<String> mediaPaths, int maxTokens, TokenSink sink) {
        List<String> paths = mediaPaths == null ? Collections.emptyList() : mediaPaths;
        if (paths.isEmpty()) return generate(handle, prompt, (String) null, maxTokens, sink);
        if (paths.size() == 1) return generate(handle, prompt, paths.get(0), maxTokens, sink);
        return "This inference engine does not support multiple media attachments";
    }

    /**
     * Writes a spoken PCM16 mono WAV for {@code text}. Default builds return an explicit
     * unsupported message; production Omni speech-out is not enabled in this Stage 1 slice.
     */
    default String synthesizeSpeech(long handle, String text, File outputWav) {
        return "Speech generation is not supported by this build";
    }

    void stop();
    void resetStop();
}
