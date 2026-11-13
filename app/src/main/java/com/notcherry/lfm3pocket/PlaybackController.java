package com.notcherry.lfm3pocket;

import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Owns one explicitly requested PCM playback session with injectable AudioTrack and audio focus. */
public final class PlaybackController implements AutoCloseable {
    public enum State { IDLE, STARTING, PLAYING, STOPPING, ERROR, CLOSED }

    public interface Track extends AutoCloseable {
        void start(int sampleRateHz, int channels, int bitsPerSample) throws Exception;
        void write(byte[] interleavedLittleEndianPcm) throws Exception;
        void stop();
        void close();
    }

    public interface Factory {
        Track open() throws Exception;
    }

    public interface Listener {
        void changed(State state, String message);
    }

    private final Factory factory;
    private final BooleanSupplier requestFocus;
    private final Runnable abandonFocus;
    private final Executor worker;
    private final Consumer<Runnable> ui;
    private final Listener listener;
    private volatile State state = State.IDLE;
    private volatile boolean stopping, closed;
    private boolean active;
    private long epoch;

    public PlaybackController(Factory factory, BooleanSupplier requestFocus, Runnable abandonFocus,
                              Executor worker, Consumer<Runnable> ui, Listener listener) {
        this.factory = factory;
        this.requestFocus = requestFocus;
        this.abandonFocus = abandonFocus;
        this.worker = worker;
        this.ui = ui;
        this.listener = listener;
    }

    public State state() { return state; }

    public synchronized boolean play(byte[] pcm16le, int sampleRateHz, int channels, int bitsPerSample) {
        if (closed || active) return false;
        if (pcm16le == null || pcm16le.length == 0 || (pcm16le.length % 2) != 0) return false;
        if (sampleRateHz < 8000 || channels != 1 || bitsPerSample != 16) return false;
        active = true;
        stopping = false;
        long turn = ++epoch;
        final byte[] copy = pcm16le.clone();
        publish(turn, State.STARTING, "Starting spoken response...");
        try {
            worker.execute(() -> play(turn, copy, sampleRateHz, channels, bitsPerSample));
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

    private void play(long turn, byte[] pcm, int sampleRateHz, int channels, int bitsPerSample) {
        boolean focusHeld = false;
        Track track = null;
        try {
            if (stopping) {
                finish(turn, State.IDLE, "Spoken response cancelled");
                return;
            }
            if (!requestFocus.getAsBoolean())
                throw new IllegalStateException("Audio focus was denied");
            focusHeld = true;
            if (stopping) {
                finish(turn, State.IDLE, "Spoken response cancelled");
                return;
            }
            track = factory.open();
            track.start(sampleRateHz, channels, bitsPerSample);
            if (!stopping) publish(turn, State.PLAYING, "Playing spoken response. Tap Stop speech when finished.");
            final int chunk = 4096;
            for (int offset = 0; offset < pcm.length && !stopping; offset += chunk) {
                int n = Math.min(chunk, pcm.length - offset);
                byte[] piece = new byte[n];
                System.arraycopy(pcm, offset, piece, 0, n);
                track.write(piece);
            }
            if (stopping) finish(turn, State.IDLE, "Spoken response stopped.");
            else finish(turn, State.IDLE, "Spoken response finished.");
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            finish(turn, State.ERROR, "Spoken playback failed: " + e.getMessage());
        } finally {
            if (track != null) {
                try { track.stop(); } catch (RuntimeException ignored) {}
                try { track.close(); } catch (RuntimeException ignored) {}
            }
            if (focusHeld) {
                try { abandonFocus.run(); } catch (RuntimeException ignored) {}
            }
        }
    }
}
