package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Handler;
import android.os.Looper;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

public final class VoiceSpokenOutputPlaybackTest {
    @Test public void defersOnCompleteUntilPlaybackIdle() throws Exception {
        AtomicBoolean completed = new AtomicBoolean();
        StubHost host = new StubHost();
        VoiceSpokenOutput spoken = new VoiceSpokenOutput(host);
        java.lang.reflect.Field pending = VoiceSpokenOutput.class.getDeclaredField("pendingComplete");
        pending.setAccessible(true);
        pending.set(spoken, (Runnable) () -> completed.set(true));
        java.lang.reflect.Field busy = VoiceSpokenOutput.class.getDeclaredField("busy");
        busy.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) busy.get(spoken)).set(true);
        assertFalse(completed.get());
        spoken.onPlaybackIdle();
        assertTrue(completed.get());
        assertFalse(spoken.isBusy());
    }

    private static final class StubHost implements VoiceSpokenOutput.Host {
        final Handler main = new Handler(Looper.getMainLooper());

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
        @Override public java.util.concurrent.Executor worker() { return Runnable::run; }
        @Override public Handler main() { return main; }
        @Override public android.content.Context app() { return null; }
        @Override public void onSpeechUi(boolean active, String status) { }
        @Override public void onPlaybackPolicy(boolean playing) { }
        @Override public void policySpeechStarted() { }
    }
}