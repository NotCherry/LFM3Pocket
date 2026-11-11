package com.notcherry.lfm3pocket;

/** Production adapter; native library remains an implementation detail. */
public final class NativeInferenceEngine implements InferenceEngine {
    private volatile long ownedHandle;
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
        return NativeBridge.generate(handle, prompt, mediaPath, maxTokens, sink::onToken);
    }

    @Override public void stop() {
        NativeBridge.requestStop(ownedHandle);
    }

    @Override public void resetStop() {
        NativeBridge.resetModelStop(ownedHandle);
    }
}
