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

    @Test public void failedConfirmationInferenceRejectsEvenWhenPartialOutputSaysYes() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "voice-confirm-failure");
        dir.mkdirs();
        File wav = new File(dir, "confirm.wav");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(wav)) {
            out.write(new byte[] { 1, 2, 3, 4 });
        }
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger approved = new AtomicInteger();
        StubCallbacks callbacks = new StubCallbacks(rejected, approved);
        callbacks.inferenceOutput = "yes";
        callbacks.inferenceError = "decode failed";
        VoiceActionRuntime runtime = new VoiceActionRuntime(callbacks, dir, () -> true,
                Runnable::run, Runnable::run, () -> new RecordingController.Source() {
                    @Override public void start() { }
                    @Override public int read(short[] buffer) { return 0; }
                    @Override public void close() { }
                });
        setPendingConfirm(runtime, "proposal-failed");
        java.lang.reflect.Field pendingWav = VoiceActionRuntime.class.getDeclaredField("pendingWav");
        pendingWav.setAccessible(true);
        pendingWav.set(runtime, wav);
        java.lang.reflect.Method confirm = VoiceActionRuntime.class.getDeclaredMethod("runConfirmPass");
        confirm.setAccessible(true);
        confirm.invoke(runtime);
        assertEquals(1, rejected.get());
        assertEquals(0, approved.get());
        assertNull(pendingConfirm(runtime));
        assertTrue(!wav.exists());
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
        assertEquals(VoiceListenSession.State.CANCELLED, session.state());
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

    @Test public void failedConfirmationInferenceCannotApproveTranscript() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "voice-confirm-fail");
        dir.mkdirs();
        File wav = new File(dir, "confirm.wav");
        java.nio.file.Files.write(wav.toPath(), new byte[] { 0, 1, 2, 3 });
        AtomicInteger approved = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger cleared = new AtomicInteger();
        ConfirmCallbacks callbacks = new ConfirmCallbacks(approved, rejected, cleared, "tak", "inference error");
        VoiceActionRuntime runtime = new VoiceActionRuntime(callbacks, dir, () -> true,
                Runnable::run, Runnable::run, silentFactory());
        setPendingConfirm(runtime, "proposal-err");
        setPendingWav(runtime, wav);
        invokeRunConfirmPass(runtime);
        assertEquals(0, approved.get());
        assertEquals(1, rejected.get());
        assertEquals(1, cleared.get());
        assertNull(pendingConfirm(runtime));
        assertTrue(!wav.exists());
        runtime.listenSessionForTests().close();
    }

    @Test public void successfulYesTranscriptApproves() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "voice-confirm-ok");
        dir.mkdirs();
        File wav = new File(dir, "confirm.wav");
        java.nio.file.Files.write(wav.toPath(), new byte[] { 0, 1, 2, 3 });
        AtomicInteger approved = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger cleared = new AtomicInteger();
        ConfirmCallbacks callbacks = new ConfirmCallbacks(approved, rejected, cleared, "yes", null);
        VoiceActionRuntime runtime = new VoiceActionRuntime(callbacks, dir, () -> true,
                Runnable::run, Runnable::run, silentFactory());
        setPendingConfirm(runtime, "proposal-ok");
        setPendingWav(runtime, wav);
        invokeRunConfirmPass(runtime);
        assertEquals(1, approved.get());
        assertEquals(0, rejected.get());
        assertEquals(1, cleared.get());
        runtime.listenSessionForTests().close();
    }

    private static void invokeRunConfirmPass(VoiceActionRuntime runtime) throws Exception {
        java.lang.reflect.Method m = VoiceActionRuntime.class.getDeclaredMethod("runConfirmPass");
        m.setAccessible(true);
        m.invoke(runtime);
    }

    private static void setPendingWav(VoiceActionRuntime runtime, File wav) throws Exception {
        java.lang.reflect.Field f = VoiceActionRuntime.class.getDeclaredField("pendingWav");
        f.setAccessible(true);
        f.set(runtime, wav);
    }

    private static RecordingController.Factory silentFactory() {
        return () -> new RecordingController.Source() {
            @Override public void start() { }
            @Override public int read(short[] buffer) { return 0; }
            @Override public void close() { }
        };
    }

    private static final class ConfirmCallbacks implements VoiceActionRuntime.Callbacks {
        private final AtomicInteger approved;
        private final AtomicInteger rejected;
        private final AtomicInteger cleared;
        private final String transcript;
        private final String error;

        ConfirmCallbacks(AtomicInteger approved, AtomicInteger rejected, AtomicInteger cleared,
                         String transcript, String error) {
            this.approved = approved;
            this.rejected = rejected;
            this.cleared = cleared;
            this.transcript = transcript;
            this.error = error;
        }

        @Override public long modelHandle() { return 1L; }
        @Override public InferenceEngine engine() {
            return new InferenceEngine() {
                @Override public long loadModel(String modelPath, String projectorPath) { return 1L; }
                @Override public void unloadModel(long handle) { }
                @Override public String generate(long handle, String prompt, String mediaPath, int maxTokens,
                        TokenSink sink) { return null; }
                @Override public String generateWithMedia(long handle, String prompt, String[] mediaPaths,
                        int maxTokens, TokenSink sink) {
                    if (transcript != null && !transcript.isEmpty()) sink.onToken(transcript);
                    return error;
                }
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
        @Override public boolean isGenerating() { return false; }
        @Override public void showConsent(String proposalId, String describe, boolean safe) { }
        @Override public void clearConsent() { cleared.incrementAndGet(); }
        @Override public void resolveConsent(String proposalId, boolean approve) {
            if (approve) approved.incrementAndGet(); else rejected.incrementAndGet();
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

    private static final class StubCallbacks implements VoiceActionRuntime.Callbacks {
        private final AtomicInteger rejected;
        private final AtomicInteger approved;
        String inferenceError;
        String inferenceOutput = "";

        StubCallbacks(AtomicInteger rejected) { this(rejected, new AtomicInteger()); }
        StubCallbacks(AtomicInteger rejected, AtomicInteger approved) {
            this.rejected = rejected;
            this.approved = approved;
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
            if (approve) approved.incrementAndGet();
            else rejected.incrementAndGet();
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