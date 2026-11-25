package com.notcherry.lfm3pocket;

import android.os.Build;

/** Production adapter; native library remains an implementation detail. */
public final class NativeInferenceEngine implements InferenceEngine {
    private volatile long ownedHandle;
    private volatile InferenceSettings settings = InferenceSettings.DEFAULT;

    @Override public void configure(InferenceSettings settings) {
        this.settings = settings == null ? InferenceSettings.DEFAULT : settings;
    }

    @Override public long loadModel(String modelPath, String projectorPath) {
        if (!supportsPhoneNativeKernels()) {
            // Refuse before DOTPROD kernels run — app stays usable with a clear error
            // instead of SIGILL (docs/adr/0001-arm64-dotprod-and-kv-residency.md).
            return 0;
        }
        long next = NativeBridge.loadModel(modelPath, projectorPath);
        if (next != 0) ownedHandle = next;
        return next;
    }

    @Override public void unloadModel(long handle) {
        NativeBridge.unloadModel(handle);
        if (ownedHandle == handle) ownedHandle = 0;
    }

    /** Drop KV/hybrid session while keeping weights mapped. */
    public void releaseSession(long handle) {
        if (handle != 0) NativeBridge.releaseSession(handle);
    }

    @Override public String generate(long handle, String prompt, String mediaPath,
                                     int maxTokens, TokenSink sink) {
        return generateWithMedia(handle, prompt, mediaPath == null ? new String[0] : new String[]{mediaPath},
                maxTokens, sink);
    }

    @Override public String generateWithMedia(long handle, String prompt, String[] mediaPaths,
                                              int maxTokens, TokenSink sink) {
        return generateWithMediaAndGrammar(handle, prompt, mediaPaths, maxTokens, null, null, sink);
    }

    @Override public String generateWithMediaAndGrammar(long handle, String prompt, String[] mediaPaths,
                                                        int maxTokens, String grammar, String grammarTrigger,
                                                        TokenSink sink) {
        InferenceSettings current = settings;
        try {
            return NativeBridge.generateWithMediaSettingsAndGrammar(handle, prompt, mediaPaths,
                    new InferenceSettings(current.contextTokens, current.threads,
                            Math.min(maxTokens, current.maxTokens), current.promptLookup),
                    grammar, grammarTrigger, sink::onToken);
        } catch (OutOfMemoryError error) {
            return "Could not process this image on this phone (out of memory). Try a smaller photo.";
        } catch (Throwable error) {
            String message = error.getMessage();
            return message == null || message.isBlank()
                    ? "Image or media processing failed."
                    : "Image or media processing failed: " + message;
        }
    }

    @Override public String generateSpeech(long handle, String prompt, SpeechOptions options, AudioSink sink) {
        SpeechOptions selected = options == null ? SpeechOptions.DEFAULT : options;
        InferenceSettings current = settings;
        return NativeBridge.generateSpeech(handle, prompt, selected, current,
                new NativeBridge.AudioSink() {
                    @Override public void onAudioFormat(int sampleRateHz, int channelCount, int bitsPerSample) {
                        sink.onAudioFormat(sampleRateHz, channelCount, bitsPerSample);
                    }
                    @Override public void onAudioPcm(byte[] interleavedLittleEndianPcm) {
                        sink.onAudioPcm(interleavedLittleEndianPcm);
                    }
                    @Override public void onAudioComplete() {
                        sink.onAudioComplete();
                    }
                });
    }

    @Override public void stop() {
        NativeBridge.requestStop(ownedHandle);
    }

    @Override public void resetStop() {
        NativeBridge.resetModelStop(ownedHandle);
    }

    @Override public boolean supportsVoiceAudioIn(long handle) {
        return handle != 0L && NativeBridge.modelSupportsAudioIn(handle);
    }

    /** Phone arm64 builds need DOTPROD; x86_64 smoke/emulator does not. */
    static boolean supportsPhoneNativeKernels() {
        boolean arm64 = false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equalsIgnoreCase(abi)) arm64 = true;
        }
        if (!arm64) return true;
        return CpuTopology.hasDotProd();
    }

    static String unsupportedCpuMessage() {
        return "This phone CPU lacks ARM DotProd. Hanami’s arm64 build needs it "
                + "(Snapdragon 845 / Cortex-A76 class or newer). The app still runs; "
                + "model load is blocked to avoid a crash. See docs/adr/0001.";
    }
}
