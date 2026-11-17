package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VoiceAudioCapabilityTest {
    @Test public void usesEngineNativeAudioProbeNotMmprojAlone() {
        InferenceEngine withAudio = new StubEngine(true);
        InferenceEngine withoutAudio = new StubEngine(false);
        assertTrue(VoiceAudioCapability.voiceActionsEnabled(withAudio, 42L));
        assertFalse(VoiceAudioCapability.voiceActionsEnabled(withoutAudio, 42L));
        assertFalse(VoiceAudioCapability.voiceActionsEnabled(withAudio, 0L));
        assertFalse(VoiceAudioCapability.voiceActionsEnabled(null, 42L));
    }

    private static final class StubEngine implements InferenceEngine {
        private final boolean audioIn;

        StubEngine(boolean audioIn) { this.audioIn = audioIn; }

        @Override public long loadModel(String modelPath, String projectorPath) { return 0; }
        @Override public void unloadModel(long handle) { }
        @Override public String generate(long handle, String prompt, String mediaPath, int maxTokens, TokenSink sink) {
            return null;
        }
        @Override public boolean supportsVoiceAudioIn(long handle) { return audioIn; }
        @Override public void stop() { }
        @Override public void resetStop() { }
    }
}