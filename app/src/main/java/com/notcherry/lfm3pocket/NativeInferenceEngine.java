package com.notcherry.lfm3pocket;

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
        return generateWithMedia(handle,prompt,mediaPath==null?new String[0]:new String[]{mediaPath},maxTokens,sink);
    }

    @Override public String generateWithMedia(long handle, String prompt, String[] mediaPaths,
                                              int maxTokens, TokenSink sink) {
        InferenceSettings current=settings;
        return NativeBridge.generateWithMediaAndSettings(handle,prompt,mediaPaths,
                new InferenceSettings(current.contextTokens,current.threads,Math.min(maxTokens,current.maxTokens)),sink::onToken);
    }

    @Override public void stop() {
        NativeBridge.requestStop(ownedHandle);
    }

    @Override public void resetStop() {
        NativeBridge.resetModelStop(ownedHandle);
    }
}
