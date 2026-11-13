package com.notcherry.lfm3pocket;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Owns one explicitly requested private WAV playback. AudioTrack I/O stays on its worker. */
public final class PlaybackController implements AutoCloseable {
    public enum State { IDLE, STARTING, PLAYING, STOPPING, ERROR, CLOSED }

    public interface Player extends AutoCloseable {
        void start(short[] pcm) throws Exception;
        /** Writes as many samples as possible; returns samples written, or -1 on failure. */
        int write(short[] pcm, int offset, int count) throws Exception;
        void stop();
        void close();
    }

    public interface Factory { Player open() throws Exception; }
    public interface Listener { void changed(State state, String message); }

    private final Factory factory;
    private final Executor worker;
    private final Consumer<Runnable> ui;
    private final Listener listener;
    private volatile State state = State.IDLE;
    private volatile boolean stopping, closed;
    private boolean active;
    private long epoch;

    public PlaybackController(Factory factory, Executor worker, Consumer<Runnable> ui, Listener listener) {
        this.factory = factory;
        this.worker = worker;
        this.ui = ui;
        this.listener = listener;
    }

    public State state() { return state; }

    public synchronized boolean play(File wav) {
        if (closed || active || wav == null) return false;
        active = true;
        stopping = false;
        long turn = ++epoch;
        publish(turn, State.STARTING, "Starting spoken response...");
        try {
            worker.execute(() -> playback(turn, wav));
        } catch (RuntimeException e) {
            finish(turn, State.ERROR, "Could not schedule spoken playback");
        }
        return true;
    }

    public synchronized void stop() {
        if (!active || closed || stopping) return;
        stopping = true;
        publish(epoch, State.STOPPING, "Stopping spoken response...");
    }

    @Override public synchronized void close() {
        closed = true;
        stopping = true;
        state = State.CLOSED;
    }

    private synchronized void publish(long turn, State next, String message) {
        if (closed || turn != epoch) return;
        state = next;
        ui.accept(() -> {
            synchronized (this) {
                if (closed || turn != epoch) return;
            }
            listener.changed(next, message);
        });
    }

    private synchronized void finish(long turn, State next, String message) {
        active = false;
        publish(turn, next, message);
    }

    private void playback(long turn, File wav) {
        try {
            if (stopping) { finish(turn, State.IDLE, "Spoken response stopped"); return; }
            short[] pcm = SpeechAudio.readPcm(wav);
            try (Player player = factory.open()) {
                if (stopping) { finish(turn, State.IDLE, "Spoken response stopped"); return; }
                player.start(pcm);
                publish(turn, State.PLAYING, "Playing spoken response. Tap Stop to interrupt.");
                int offset = 0;
                while (offset < pcm.length && !stopping) {
                    int written = player.write(pcm, offset, pcm.length - offset);
                    if (written < 0) throw new IllegalStateException("Audio playback write failed");
                    if (written == 0) Thread.sleep(10);
                    else offset += written;
                }
                player.stop();
            }
            finish(turn, State.IDLE, stopping ? "Spoken response stopped" : "Spoken response finished");
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            finish(turn, State.ERROR, "Spoken playback failed: " + e.getMessage());
        }
    }
}
