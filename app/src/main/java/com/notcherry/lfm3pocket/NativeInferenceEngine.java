package com.notcherry.lfm3pocket;

import java.io.File;
import java.util.Collections;
import java.util.List;

/** Production adapter; native library remains an implementation detail. */
public final class NativeInferenceEngine implements InferenceEngine {
    private volatile long ownedHandle;
    private volatile InferenceSettings settings=InferenceSettings.DEFAULT;
    @Override public void configure(InferenceSettings settings) { this.settings=settings; }
    @Override public long loadModel(String modelPath, String projectorPath) {
        long next = NativeBridge.loadModel(modelPath, projectorPath);
        if (next != 0) ownedHandle = next;
        return next;
    }

    @Override public void unloadModel(long handle) {
        NativeBridge.unloadModel(handle);
        if (ownedHandle == handle) ownedHandle = 0;
    }

    @Override public String generate(long handle, String prompt, String mediaPath,
                                     int maxTokens, TokenSink sink) {
        List<String> paths = mediaPath == null || mediaPath.isEmpty()
                ? Collections.emptyList() : Collections.singletonList(mediaPath);
        return generate(handle, prompt, paths, maxTokens, sink);
    }

    @Override public String generate(long handle, String prompt, List<String> mediaPaths,
                                     int maxTokens, TokenSink sink) {
        InferenceSettings current=settings;
        String[] paths = mediaPaths == null || mediaPaths.isEmpty()
                ? new String[0] : mediaPaths.toArray(new String[0]);
        return NativeBridge.generateMultiWithSettings(handle, prompt, paths,
                new InferenceSettings(current.contextTokens, current.threads,
                        Math.min(maxTokens, current.maxTokens)), sink::onToken);
    }

    @Override public String synthesizeSpeech(long handle, String text, File outputWav) {
        return "Speech generation is not supported by this build";
    }

    @Override public void stop() {
        NativeBridge.requestStop(ownedHandle);
    }

    @Override public void resetStop() {
        NativeBridge.resetModelStop(ownedHandle);
    }
}
