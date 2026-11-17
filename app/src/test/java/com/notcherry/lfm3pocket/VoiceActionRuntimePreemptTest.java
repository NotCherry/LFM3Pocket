package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class VoiceActionRuntimePreemptTest {
    @Test public void pttDuringConfirmListenRejectsAndStartsCommand() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "voice-preempt");
        dir.mkdirs();
        LinkedBlockingQueue<Runnable> workerTasks = new LinkedBlockingQueue<>();
        AtomicInteger rejected = new AtomicInteger();
        StubCallbacks callbacks = new StubCallbacks(rejected);
        RecordingController.Factory factory = () -> new RecordingController.Source() {
            @Override public void start() { }
            @Override public int read(short[] buffer) {
                java.util.Arrays.fill(buffer, (short) 1);
                return buffer.length;
            }
            @Override public void close() { }
        };
        VoiceActionRuntime runtime = new VoiceActionRuntime(callbacks, dir, () -> true,
                workerTasks::offer, Runnable::run, factory);
        setPendingConfirm(runtime, "proposal-1");
        assertTrue(runtime.listenSessionForTests().startTimed(3_000));
        assertEquals(VoiceListenSession.State.LISTENING, runtime.listenSessionForTests().state());
        assertTrue(runtime.pttPressed());
        assertEquals(1, rejected.get());
        assertNull(pendingConfirm(runtime));
        assertEquals(VoiceListenSession.State.LISTENING, runtime.listenSessionForTests().state());
        runtime.listenSessionForTests().close();
    }

    private static void drain(LinkedBlockingQueue<Runnable> tasks) {
        while (!tasks.isEmpty()) tasks.poll().run();
    }

  /** Matches {@link VoiceListenSessionTest}: cancel must happen before a new PTT can start. */
    @Test public void cancelWhileListeningThenStartPttSucceeds() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "voice-preempt-cancel");
        dir.mkdirs();
        RecordingController.Factory factory = () -> new RecordingController.Source() {
            @Override public void start() { }
            @Override public int read(short[] buffer) { return 0; }
            @Override public void close() { }
        };
        LinkedBlockingQueue<Runnable> cancelWorker = new LinkedBlockingQueue<>();
        VoiceListenSession session = new VoiceListenSession(dir, factory, () -> true, cancelWorker::offer,
                Runnable::run, Executors.newSingleThreadScheduledExecutor(),
                new VoiceListenSession.Listener() {
                    @Override public void changed(VoiceListenSession.State state, String message) { }
                    @Override public void wavReady(File wav) { }
                }, VoiceListenLimits.MAX_ACTION_SAMPLES);
        assertTrue(session.startTimed(2_000));
        assertEquals(VoiceListenSession.State.LISTENING, session.state());
        assertEquals(false, session.startPtt());
        session.cancel("preempt");
        assertEquals(VoiceListenSession.State.IDLE, session.state());
        assertTrue(session.startPtt());
        session.close();
    }

    private static void setPendingConfirm(VoiceActionRuntime runtime, String id) throws Exception {
        java.lang.reflect.Field mode = VoiceActionRuntime.class.getDeclaredField("mode");
        mode.setAccessible(true);
        Class<?> modeType = Class.forName("com.notcherry.lfm3pocket.VoiceActionRuntime$Mode");
        @SuppressWarnings({ "unchecked", "rawtypes" })
        Object confirm = Enum.valueOf((Class) modeType, "CONFIRM");
        mode.set(runtime, confirm);
        java.lang.reflect.Field pending = VoiceActionRuntime.class.getDeclaredField("pendingConfirmId");
        pending.setAccessible(true);
        pending.set(runtime, id);
    }

    private static String pendingConfirm(VoiceActionRuntime runtime) throws Exception {
        java.lang.reflect.Field pending = VoiceActionRuntime.class.getDeclaredField("pendingConfirmId");
        pending.setAccessible(true);
        return (String) pending.get(runtime);
    }

    private static final class StubCallbacks implements VoiceActionRuntime.Callbacks {
        private final AtomicInteger rejected;

        StubCallbacks(AtomicInteger rejected) { this.rejected = rejected; }

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
            return Executors.newSingleThreadExecutor();
        }
        @Override public android.os.Handler main() { return null; }
        @Override public android.content.Context app() { return null; }
        @Override public SharedPreferences modelPrefs() { return null; }
        @Override public ToolCoordinator toolCoordinator() { return null; }
        @Override public boolean isGenerating() { return false; }
        @Override public void showConsent(String proposalId, String describe, boolean safe) { }
        @Override public void clearConsent() { }
        @Override public void resolveConsent(String proposalId, boolean approve) {
            if (!approve) rejected.incrementAndGet();
        }
        @Override public void stopTurn() { }
        @Override public Locale userLocale() { return Locale.US; }
        @Override public Consumer<String> onVoiceStatus() { return s -> { }; }
        @Override public void policyListenStarted() { }
        @Override public void policyListenFinished() { }
        @Override public void policyVoiceThinking() { }
        @Override public void policySpeechStarted() { }
        @Override public void policyPlaybackStarted() { }
        @Override public void policySpeechFinished() { }
        @Override public void policyGenerationFinished() { }
    }
}