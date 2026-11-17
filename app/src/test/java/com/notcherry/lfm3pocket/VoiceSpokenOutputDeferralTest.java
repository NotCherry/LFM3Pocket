package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class VoiceSpokenOutputDeferralTest {
    @Test public void busySpeakDoesNotRunOnCompleteImmediately() {
        StubHost host = new StubHost();
        VoiceSpokenOutput spoken = new VoiceSpokenOutput(host);
        markBusy(spoken, true);
        AtomicBoolean chained = new AtomicBoolean();
        spoken.speakExact("queued", Locale.US, () -> chained.set(true));
        assertFalse(chained.get());
    }

    @Test public void playbackIdleRunsDeferredOnSameThreadWithoutPost() {
        StubHost host = new StubHost();
        VoiceSpokenOutput spoken = new VoiceSpokenOutput(host);
        long gen = spoken.currentSpeechGeneration();
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        AtomicBoolean ran = new AtomicBoolean();
        setPendingComplete(spoken, gen, () -> {
            ran.set(true);
            ranOn.set(Thread.currentThread());
        });
        Thread caller = Thread.currentThread();
        spoken.onPlaybackIdle();
        assertTrue(ran.get());
        assertEquals(caller, ranOn.get());
    }

    @Test public void stopDuringPendingCompletionIsNoOp() {
        StubHost host = new StubHost();
        VoiceSpokenOutput spoken = new VoiceSpokenOutput(host);
        long gen = spoken.currentSpeechGeneration();
        AtomicBoolean ran = new AtomicBoolean();
        setPendingComplete(spoken, gen, () -> ran.set(true));
        spoken.stop();
        spoken.onPlaybackIdle();
        assertFalse(ran.get());
    }

    @Test public void invalidateDeferredCompletionsClearsSpeakQueue() throws Exception {
        StubHost host = new StubHost();
        VoiceSpokenOutput spoken = new VoiceSpokenOutput(host);
        markBusy(spoken, true);
        spoken.speakExact("stale", Locale.US, null);
        assertEquals(1, queueSize(spoken));
        spoken.invalidateDeferredCompletions();
        assertEquals(0, queueSize(spoken));
        markBusy(spoken, false);
        spoken.onPlaybackIdle();
        assertFalse(host.speakRequested.get());
    }

    @Test public void drainQueueDoesNotDropWhenBusyClaimFails() throws Exception {
        StubHost host = new StubHost();
        VoiceSpokenOutput spoken = new VoiceSpokenOutput(host);
        markBusy(spoken, true);
        spoken.speakExact("keep-me", Locale.US, null);
        assertEquals(1, queueSize(spoken));
        invokeDrainQueue(spoken);
        assertEquals(1, queueSize(spoken));
        markBusy(spoken, false);
        spoken.onPlaybackIdle();
        assertTrue(host.speakRequested.get());
    }

    @Test public void playbackDeferralThenDrainStartsQueuedUtterance() {
        StubHost host = new StubHost();
        VoiceSpokenOutput spoken = new VoiceSpokenOutput(host);
        markBusy(spoken, true);
        spoken.speakExact("second", Locale.US, null);
        long gen = spoken.currentSpeechGeneration();
        setPendingComplete(spoken, gen, null);
        assertTrue(spoken.isBusy());
        spoken.onPlaybackIdle();
        assertTrue(host.speakRequested.get());
    }

    private static int queueSize(VoiceSpokenOutput spoken) throws Exception {
        java.lang.reflect.Field queue = VoiceSpokenOutput.class.getDeclaredField("speakQueue");
        queue.setAccessible(true);
        return ((java.util.Queue<?>) queue.get(spoken)).size();
    }

    private static void invokeDrainQueue(VoiceSpokenOutput spoken) throws Exception {
        java.lang.reflect.Method drain = VoiceSpokenOutput.class.getDeclaredMethod("drainQueue");
        drain.setAccessible(true);
        drain.invoke(spoken);
    }

    private static void markBusy(VoiceSpokenOutput spoken, boolean value) {
        try {
            java.lang.reflect.Field busy = VoiceSpokenOutput.class.getDeclaredField("busy");
            busy.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicBoolean) busy.get(spoken)).set(value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setPendingComplete(VoiceSpokenOutput spoken, long generation, Runnable runnable) {
        try {
            java.lang.reflect.Field pending = VoiceSpokenOutput.class.getDeclaredField("pendingComplete");
            pending.setAccessible(true);
            pending.set(spoken, runnable);
            java.lang.reflect.Field gen = VoiceSpokenOutput.class.getDeclaredField("pendingCompleteGeneration");
            gen.setAccessible(true);
            gen.setLong(spoken, generation);
            markBusy(spoken, true);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static final class StubHost implements VoiceSpokenOutput.Host {
        final AtomicBoolean speakRequested = new AtomicBoolean();

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
        @Override public PlaybackController playback() { return noopPlayback(); }
        @Override public java.util.concurrent.Executor worker() { return Runnable::run; }
        @Override public android.os.Handler main() { return null; }
        @Override public android.content.Context app() { return null; }
        @Override public void onSpeechUi(boolean active, String status) { }
        @Override public void onPlaybackPolicy(boolean playing) { }
        @Override public void policySpeechStarted() { speakRequested.set(true); }
    }

    private static PlaybackController noopPlayback() {
        return new PlaybackController(() -> { throw new UnsupportedOperationException(); },
                () -> true, () -> { }, Runnable::run, Runnable::run, (s, m) -> { });
    }
}