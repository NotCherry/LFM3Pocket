package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;
import org.junit.Test;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class ModelRuntimeVoiceCallbacksTest {
    @Test public void delegatesIsGeneratingWithoutRecursion() {
        AtomicInteger generatingChecks = new AtomicInteger();
        RecordingHost host = new RecordingHost();
        host.generating = true;
        host.generatingChecks = generatingChecks;
        ModelRuntimeVoiceCallbacks callbacks = new ModelRuntimeVoiceCallbacks(host);
        assertTrue(callbacks.isGenerating());
        assertEquals(1, generatingChecks.get());
        host.generating = false;
        assertFalse(callbacks.isGenerating());
        assertEquals(2, generatingChecks.get());
    }

    @Test public void delegatesClearConsentWithoutRecursion() {
        RecordingHost host = new RecordingHost();
        ModelRuntimeVoiceCallbacks callbacks = new ModelRuntimeVoiceCallbacks(host);
        callbacks.clearConsent();
        assertEquals(1, host.clearConsentCalls);
    }

    private static final class RecordingHost implements ModelRuntimeVoiceCallbacks.Host {
        boolean generating;
        int clearConsentCalls;
        AtomicInteger generatingChecks = new AtomicInteger();

        @Override public long modelHandle() { return 1L; }
        @Override public InferenceEngine engine() {
            return new InferenceEngine() {
                @Override public long loadModel(String modelPath, String projectorPath) { return 0; }
                @Override public void unloadModel(long handle) { }
                @Override public String generate(long handle, String prompt, String mediaPath, int maxTokens,
                                                 TokenSink sink) { return null; }
                @Override public void stop() { }
                @Override public void resetStop() { }
            };
        }
        @Override public PlaybackController playback() { return null; }
        @Override public ExecutorService worker() { return Executors.newSingleThreadExecutor(); }
        @Override public android.os.Handler main() { return null; }
        @Override public android.content.Context app() { return null; }
        @Override public SharedPreferences modelPrefs() { return null; }
        @Override public ToolCoordinator toolCoordinator() { return null; }
        @Override public boolean isGenerating() {
            generatingChecks.incrementAndGet();
            return generating;
        }
        @Override public void showConsent(String proposalId, String describe, boolean safe) { }
        @Override public void clearConsent() { clearConsentCalls++; }
        @Override public void resolveConsent(String proposalId, boolean approve) { }
        @Override public void stopTurn() { }
        @Override public Locale userLocale() { return Locale.US; }
        @Override public void onVoiceStatus(String status) { }
        @Override public void policyListenStarted() { }
        @Override public void policyListenFinished() { }
        @Override public void policyVoiceThinking() { }
        @Override public void policySpeechStarted() { }
        @Override public void policyPlaybackStarted() { }
        @Override public void policySpeechFinished() { }
        @Override public void policyGenerationFinished() { }
    }
}