package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class VoiceActionRuntimeStartGuardTest extends HanamiConcurrencyUnitTest {
    private static final String MIC_REQUIRED = "Microphone permission is required for Talk.";
    private static final String REPLY_RUNNING = "Finish the current reply before using Talk.";

    @Test public void beginShadeTalkPublishesMicRequiredBeforeGeneratingGuard() {
        AtomicReference<String> status = new AtomicReference<>("");
        GuardCallbacks callbacks = new GuardCallbacks(status, true);
        VoiceActionRuntime runtime = newRuntime(callbacks, () -> false);
        try {
            runtime.beginShadeTalk();
            assertEquals(MIC_REQUIRED, status.get());
            assertEquals(VoiceListenSession.State.IDLE, runtime.listenSessionForTests().state());
        } finally {
            runtime.close();
        }
    }

    @Test public void beginShadeTalkPublishesReplyRunningWhenChatGenerating() {
        AtomicReference<String> status = new AtomicReference<>("");
        GuardCallbacks callbacks = new GuardCallbacks(status, true);
        VoiceActionRuntime runtime = newRuntime(callbacks, () -> true);
        try {
            runtime.beginShadeTalk();
            assertEquals(REPLY_RUNNING, status.get());
            assertEquals(VoiceListenSession.State.IDLE, runtime.listenSessionForTests().state());
        } finally {
            runtime.close();
        }
    }

    @Test public void pttPressedPublishesMicRequiredAndDoesNotListen() {
        AtomicReference<String> status = new AtomicReference<>("");
        GuardCallbacks callbacks = new GuardCallbacks(status, false);
        VoiceActionRuntime runtime = newRuntime(callbacks, () -> false);
        try {
            assertFalse(runtime.pttPressed());
            assertEquals(MIC_REQUIRED, status.get());
            assertEquals(VoiceListenSession.State.IDLE, runtime.listenSessionForTests().state());
        } finally {
            runtime.close();
        }
    }

    @Test public void pttPressedPublishesReplyRunningWhenChatGenerating() {
        AtomicReference<String> status = new AtomicReference<>("");
        GuardCallbacks callbacks = new GuardCallbacks(status, true);
        VoiceActionRuntime runtime = newRuntime(callbacks, () -> true);
        try {
            assertFalse(runtime.pttPressed());
            assertEquals(REPLY_RUNNING, status.get());
        } finally {
            runtime.close();
        }
    }

    @Test public void beginAccessibilityTalkPublishesMicRequiredAndDoesNotListen() {
        AtomicReference<String> status = new AtomicReference<>("");
        GuardCallbacks callbacks = new GuardCallbacks(status, false);
        VoiceActionRuntime runtime = newRuntime(callbacks, () -> false);
        try {
            assertFalse(runtime.beginAccessibilityTalk());
            assertEquals(MIC_REQUIRED, status.get());
            assertEquals(VoiceListenSession.State.IDLE, runtime.listenSessionForTests().state());
        } finally {
            runtime.close();
        }
    }

    @Test public void beginAccessibilityTalkPublishesReplyRunningWhenChatGenerating() {
        AtomicReference<String> status = new AtomicReference<>("");
        GuardCallbacks callbacks = new GuardCallbacks(status, true);
        VoiceActionRuntime runtime = newRuntime(callbacks, () -> true);
        try {
            assertFalse(runtime.beginAccessibilityTalk());
            assertEquals(REPLY_RUNNING, status.get());
        } finally {
            runtime.close();
        }
    }

    private static VoiceActionRuntime newRuntime(GuardCallbacks callbacks, java.util.function.BooleanSupplier mic) {
        File dir = new File(System.getProperty("java.io.tmpdir"), "voice-start-guard");
        dir.mkdirs();
        return new VoiceActionRuntime(callbacks, dir, mic, Runnable::run, Runnable::run, () -> new RecordingController.Source() {
            @Override public void start() { }
            @Override public int read(short[] buffer) { return 0; }
            @Override public void close() { }
        });
    }

    private static final class GuardCallbacks implements VoiceActionRuntime.Callbacks {
        private final AtomicReference<String> status;
        private final boolean generating;

        GuardCallbacks(AtomicReference<String> status, boolean generating) {
            this.status = status;
            this.generating = generating;
        }

        @Override public long modelHandle() { return 1L; }
        @Override public InferenceEngine engine() {
            return new InferenceEngine() {
                @Override public long loadModel(String modelPath, String projectorPath) { return 1L; }
                @Override public void unloadModel(long handle) { }
                @Override public String generate(long handle, String prompt, String mediaPath, int maxTokens,
                        TokenSink sink) { return null; }
                @Override public boolean supportsVoiceAudioIn(long handle) { return true; }
                @Override public void stop() { }
                @Override public void resetStop() { }
            };
        }
        @Override public PlaybackController playback() {
            return new PlaybackController(() -> { throw new UnsupportedOperationException(); },
                    () -> true, () -> { }, Runnable::run, Runnable::run, (s, m) -> { });
        }
        @Override public java.util.concurrent.ExecutorService worker() {
            return java.util.concurrent.Executors.newSingleThreadExecutor();
        }
        @Override public android.os.Handler main() { return null; }
        @Override public android.content.Context app() { return null; }
        @Override public SharedPreferences modelPrefs() { return null; }
        @Override public ToolCoordinator toolCoordinator() { return null; }
        @Override public boolean isGenerating() { return generating; }
        @Override public void showConsent(String proposalId, String describe, boolean safe) { }
        @Override public void clearConsent() { }
        @Override public void resolveConsent(String proposalId, boolean approve) { }
        @Override public void stopTurn() { }
        @Override public Locale userLocale() { return Locale.US; }
        @Override public Consumer<String> onVoiceStatus() { return status::set; }
        @Override public void policyListenStarted() { }
        @Override public void policyListenFinished() { }
        @Override public void policyVoiceThinking() { }
        @Override public void policySpeechStarted() { }
        @Override public void policyPlaybackStarted() { }
        @Override public void policySpeechFinished() { }
        @Override public void policyGenerationFinished() { }
    }
}