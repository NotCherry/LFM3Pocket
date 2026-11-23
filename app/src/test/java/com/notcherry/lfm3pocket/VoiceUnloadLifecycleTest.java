package com.notcherry.lfm3pocket;

import static org.junit.Assert.*;

import android.content.SharedPreferences;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class VoiceUnloadLifecycleTest extends HanamiConcurrencyUnitTest {
    @Test public void unloadDuringCommandCaptureStopsMicAndDeletesArtifacts() throws Exception {
        for (String cause : unloadCauses()) {
            File dir = Files.createTempDirectory("voice-unload-command-" + cause).toFile();
            ExecutorService worker = Executors.newSingleThreadExecutor();
            VoiceActionRuntime runtime = null;
            TrackingCallbacks callbacks = null;
            try {
                BlockingSource source = new BlockingSource();
                callbacks = new TrackingCallbacks();
                runtime = new VoiceActionRuntime(callbacks, dir, () -> true,
                        worker, Runnable::run, () -> source);
                setMode(runtime, "COMMAND");
                assertTrue(runtime.listenSessionForTests().startTimed(30_000));
                assertTrue(awaitListening(runtime, 2000));
                callbacks.listenStarted.set(0);
                runtime.cancelAll("Model unloading: " + cause);
                assertFalse(runtime.listenSessionForTests().state() == VoiceListenSession.State.LISTENING);
                assertNull(pendingWav(runtime));
                awaitNoRecordingArtifacts(dir, 3000);
                assertEquals(0, callbacks.listenStarted.get()); // no restart
            } finally {
                if (runtime != null) runtime.close();
                if (callbacks != null) callbacks.close();
                worker.shutdownNow();
                worker.awaitTermination(2, TimeUnit.SECONDS);
                deleteTree(dir);
            }
        }
    }

    @Test public void unloadDuringConfirmCaptureRejectsOnceNeverApprovesAndCleans() throws Exception {
        for (String cause : unloadCauses()) {
            File dir = Files.createTempDirectory("voice-unload-confirm-" + cause).toFile();
            ExecutorService worker = Executors.newSingleThreadExecutor();
            VoiceActionRuntime runtime = null;
            TrackingCallbacks callbacks = null;
            try {
                BlockingSource source = new BlockingSource();
                callbacks = new TrackingCallbacks();
                runtime = new VoiceActionRuntime(callbacks, dir, () -> true,
                        worker, Runnable::run, () -> source);
                File wav = new File(dir, "pending-confirm.wav");
                Files.write(wav.toPath(), new byte[] { 1, 2, 3, 4 });
                setPendingConfirm(runtime, "proposal-" + cause);
                setPendingWav(runtime, wav);
                assertTrue(runtime.listenSessionForTests().startTimed(30_000));
                assertTrue(awaitListening(runtime, 2000));
                runtime.cancelAll("Model unloading: " + cause);
                assertEquals("cause=" + cause, 1, callbacks.rejected.get());
                assertEquals("cause=" + cause, 0, callbacks.approved.get());
                assertEquals("cause=" + cause, 1, callbacks.cleared.get());
                assertNull(pendingConfirm(runtime));
                assertNull(pendingWav(runtime));
                assertFalse(wav.exists());
                assertFalse(runtime.listenSessionForTests().state() == VoiceListenSession.State.LISTENING);
                awaitNoRecordingArtifacts(dir, 3000);
                runtime.cancelAll("Model unloading: " + cause + "-repeat");
                assertEquals("reject must be once: " + cause, 1, callbacks.rejected.get());
            } finally {
                if (runtime != null) runtime.close();
                if (callbacks != null) callbacks.close();
                worker.shutdownNow();
                worker.awaitTermination(2, TimeUnit.SECONDS);
                deleteTree(dir);
            }
        }
    }

    @Test public void queuedConfirmPassCannotApproveAfterUnloadCancel() throws Exception {
        File dir = Files.createTempDirectory("voice-unload-queued-confirm").toFile();
        VoiceActionRuntime runtime = null;
        TrackingCallbacks callbacks = null;
        try {
            File wav = new File(dir, "confirm.wav");
            Files.write(wav.toPath(), new byte[] { 1, 2, 3, 4 });
            callbacks = new TrackingCallbacks();
            callbacks.inferenceOutput = "yes";
            runtime = new VoiceActionRuntime(callbacks, dir, () -> true,
                    Runnable::run, Runnable::run, () -> new RecordingController.Source() {
                        @Override public void start() { }
                        @Override public int read(short[] buffer) { return 0; }
                        @Override public void close() { }
                    });
            setPendingConfirm(runtime, "proposal-queued");
            setPendingWav(runtime, wav);
            runtime.cancelAll("Model unloading");
            invokeRunConfirmPass(runtime);
            assertEquals(1, callbacks.rejected.get());
            assertEquals(0, callbacks.approved.get());
            assertFalse(wav.exists());
        } finally {
            if (runtime != null) runtime.close();
            if (callbacks != null) callbacks.close();
            deleteTree(dir);
        }
    }

    private static String[] unloadCauses() {
        return new String[] { "explicit", "fgs-timeout", "recents", "memory-trim" };
    }

    private static boolean awaitListening(VoiceActionRuntime runtime, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (runtime.listenSessionForTests().state() == VoiceListenSession.State.LISTENING) return true;
            Thread.sleep(10);
        }
        return runtime.listenSessionForTests().state() == VoiceListenSession.State.LISTENING;
    }

    private static void awaitNoRecordingArtifacts(File dir, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!hasPartialArtifact(dir)) return;
            Thread.sleep(10);
        }
        assertNoRecordingArtifacts(dir);
    }

    private static boolean hasPartialArtifact(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File file : files) {
            if (file.getName().endsWith(".partial")) return true;
        }
        return false;
    }

    private static void assertNoRecordingArtifacts(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            assertFalse("partial artifact remains: " + name, name.endsWith(".partial"));
        }
    }

    private static void setMode(VoiceActionRuntime runtime, String name) throws Exception {
        java.lang.reflect.Field mode = VoiceActionRuntime.class.getDeclaredField("mode");
        mode.setAccessible(true);
        Class<?> modeType = Class.forName("com.notcherry.lfm3pocket.VoiceActionRuntime$Mode");
        @SuppressWarnings({ "unchecked", "rawtypes" })
        Object value = Enum.valueOf((Class) modeType, name);
        mode.set(runtime, value);
    }

    private static void setPendingConfirm(VoiceActionRuntime runtime, String id) throws Exception {
        setMode(runtime, "CONFIRM");
        java.lang.reflect.Field pending = VoiceActionRuntime.class.getDeclaredField("pendingConfirmId");
        pending.setAccessible(true);
        pending.set(runtime, id);
    }

    private static String pendingConfirm(VoiceActionRuntime runtime) throws Exception {
        java.lang.reflect.Field pending = VoiceActionRuntime.class.getDeclaredField("pendingConfirmId");
        pending.setAccessible(true);
        return (String) pending.get(runtime);
    }

    private static void setPendingWav(VoiceActionRuntime runtime, File wav) throws Exception {
        java.lang.reflect.Field f = VoiceActionRuntime.class.getDeclaredField("pendingWav");
        f.setAccessible(true);
        f.set(runtime, wav);
    }

    private static File pendingWav(VoiceActionRuntime runtime) throws Exception {
        java.lang.reflect.Field f = VoiceActionRuntime.class.getDeclaredField("pendingWav");
        f.setAccessible(true);
        return (File) f.get(runtime);
    }

    private static void invokeRunConfirmPass(VoiceActionRuntime runtime) throws Exception {
        java.lang.reflect.Method m = VoiceActionRuntime.class.getDeclaredMethod("runConfirmPass");
        m.setAccessible(true);
        m.invoke(runtime);
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private static final class BlockingSource implements RecordingController.Source {
        volatile boolean closed;
        @Override public void start() { }
        @Override public int read(short[] buffer) throws Exception {
            if (closed) throw new java.io.IOException("closed");
            java.util.Arrays.fill(buffer, (short) 7);
            Thread.sleep(5);
            return buffer.length;
        }
        @Override public void close() { closed = true; }
    }

    private static final class TrackingCallbacks implements VoiceActionRuntime.Callbacks {
        final AtomicInteger approved = new AtomicInteger();
        final AtomicInteger rejected = new AtomicInteger();
        final AtomicInteger cleared = new AtomicInteger();
        final AtomicInteger listenStarted = new AtomicInteger();
        final AtomicInteger listenFinished = new AtomicInteger();
        final ExecutorService callbackWorker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "voice-test-callback");
            t.setDaemon(true);
            return t;
        });
        String inferenceOutput = "";
        String inferenceError;

        void close() {
            callbackWorker.shutdownNow();
            try { callbackWorker.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        @Override public long modelHandle() { return 1L; }
        @Override public InferenceEngine engine() {
            return new InferenceEngine() {
                @Override public long loadModel(String modelPath, String projectorPath) { return 1L; }
                @Override public void unloadModel(long handle) { }
                @Override public String generate(long handle, String prompt, String mediaPath, int maxTokens,
                        TokenSink sink) { return generateWithMedia(handle, prompt, null, maxTokens, sink); }
                @Override public String generateWithMedia(long handle, String prompt, String[] mediaPaths,
                        int maxTokens, TokenSink sink) {
                    if (sink != null && inferenceOutput != null && !inferenceOutput.isEmpty()) sink.onToken(inferenceOutput);
                    return inferenceError;
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
        @Override public java.util.concurrent.ExecutorService worker() { return callbackWorker; }
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
        @Override public void policyListenStarted() { listenStarted.incrementAndGet(); }
        @Override public void policyListenFinished() { listenFinished.incrementAndGet(); }
        @Override public void policyVoiceThinking() { }
        @Override public void policySpeechStarted() { }
        @Override public void policyPlaybackStarted() { }
        @Override public void policySpeechFinished() { }
        @Override public void policyGenerationFinished() { }
    }
}
