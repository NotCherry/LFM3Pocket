package com.notcherry.lfm3pocket;

/** Production adapter; native library remains an implementation detail. */
public final class NativeInferenceEngine implements InferenceEngine {
    @Override public long loadModel(String modelPath, String projectorPath) {
        return NativeBridge.loadModel(modelPath, projectorPath);
    }

    @Override public void unloadModel(long handle) {
        NativeBridge.unloadModel(handle);
    }

    @Override public String generate(long handle, String prompt, String mediaPath,
                                     int maxTokens, TokenSink sink) {
        return NativeBridge.generate(handle, prompt, mediaPath, maxTokens, sink::onToken);
    }

    @Override public void stop() {
        NativeBridge.stop();
    }

    @Override public void resetStop() {
        NativeBridge.resetStop();
    }
}
