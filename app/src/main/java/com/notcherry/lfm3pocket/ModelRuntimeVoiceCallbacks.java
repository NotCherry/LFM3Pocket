package com.notcherry.lfm3pocket;

import android.content.SharedPreferences;
import android.os.Handler;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** Explicit delegation from {@link VoiceActionRuntime} into {@link ModelRuntime} (no self-recursion). */
final class ModelRuntimeVoiceCallbacks implements VoiceActionRuntime.Callbacks {
    interface Host {
        long modelHandle();
        InferenceEngine engine();
        PlaybackController playback();
        ExecutorService worker();
        Handler main();
        android.content.Context app();
        SharedPreferences modelPrefs();
        ToolCoordinator toolCoordinator();
        boolean isGenerating();
        void showConsent(String proposalId, String describe, boolean safe);
        void clearConsent();
        void resolveConsent(String proposalId, boolean approve);
        void stopTurn();
        Locale userLocale();
        void policyListenStarted();
        void policyListenFinished();
        void policyVoiceThinking();
        void policySpeechStarted();
        void policyPlaybackStarted();
        void policySpeechFinished();
        void policyGenerationFinished();
    }

    private final Host host;

    ModelRuntimeVoiceCallbacks(Host host) {
        this.host = host;
    }

    @Override public long modelHandle() { return host.modelHandle(); }
    @Override public InferenceEngine engine() { return host.engine(); }
    @Override public PlaybackController playback() { return host.playback(); }
    @Override public ExecutorService worker() { return host.worker(); }
    @Override public Handler main() { return host.main(); }
    @Override public android.content.Context app() { return host.app(); }
    @Override public SharedPreferences modelPrefs() { return host.modelPrefs(); }
    @Override public ToolCoordinator toolCoordinator() { return host.toolCoordinator(); }
    @Override public boolean isGenerating() { return host.isGenerating(); }
    @Override public void showConsent(String id, String text, boolean safe) { host.showConsent(id, text, safe); }
    @Override public void clearConsent() { host.clearConsent(); }
    @Override public void resolveConsent(String proposalId, boolean approve) { host.resolveConsent(proposalId, approve); }
    @Override public void stopTurn() { host.stopTurn(); }
    @Override public Locale userLocale() { return host.userLocale(); }
    @Override public Consumer<String> onVoiceStatus() { return status -> { }; }
    @Override public void policyListenStarted() { host.policyListenStarted(); }
    @Override public void policyListenFinished() { host.policyListenFinished(); }
    @Override public void policyVoiceThinking() { host.policyVoiceThinking(); }
    @Override public void policySpeechStarted() { host.policySpeechStarted(); }
    @Override public void policyPlaybackStarted() { host.policyPlaybackStarted(); }
    @Override public void policySpeechFinished() { host.policySpeechFinished(); }
    @Override public void policyGenerationFinished() { host.policyGenerationFinished(); }
}