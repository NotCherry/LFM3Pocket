package com.notcherry.lfm3pocket;

import java.io.File;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Timed or PTT-bound listen windows for voice actions. Owns a dedicated {@link RecordingController}
 * with a tighter sample cap than chat capture.
 */
public final class VoiceListenSession implements AutoCloseable {
    public enum State { IDLE, LISTENING, READY, CANCELLED, TIMEOUT, ERROR }

    public interface Listener {
        void changed(State state, String message);
        void wavReady(File wav);
    }

    private final RecordingController controller;
    private final Listener listener;
    private final ScheduledExecutorService timeoutExecutor;
    private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>();
    private volatile State state = State.IDLE;
    private volatile boolean closed;

    public VoiceListenSession(File directory, RecordingController.Factory factory,
                              BooleanSupplier permission, java.util.concurrent.Executor worker,
                              Consumer<Runnable> ui, ScheduledExecutorService timeoutExecutor,
                              Listener listener, int maxSamples) {
        this.timeoutExecutor = timeoutExecutor;
        this.listener = listener;
        this.controller = new RecordingController(directory, factory, permission, worker, ui,
                (recState, message, completed) -> onRecording(recState, message, completed), maxSamples);
    }

    public State state() { return state; }

  /** Start an automatic timed listen (shade Talk). */
    public synchronized boolean startTimed(int timeoutMs) {
        if (closed || state == State.LISTENING) return false;
        cancelTimeout();
        set(State.LISTENING, "Listening…");
        if (!controller.start()) {
            set(State.ERROR, "Could not start microphone");
            return false;
        }
        if (timeoutMs > 0) scheduleTimeout(timeoutMs);
        return true;
    }

  /** PTT press — manual stop ends the utterance. */
    public synchronized boolean startPtt() {
        if (closed || state == State.LISTENING) return false;
        cancelTimeout();
        set(State.LISTENING, "Listening…");
        if (!controller.start()) {
            set(State.ERROR, "Could not start microphone");
            return false;
        }
        return true;
    }

    public synchronized void stopPtt() {
        if (state != State.LISTENING) return;
        controller.stop();
    }

    public synchronized void cancel(String reason) {
        cancelTimeout();
        if (state == State.LISTENING) {
            controller.cancel();
            set(State.CANCELLED, reason == null ? "Cancelled" : reason);
        }
    }

    @Override public synchronized void close() {
        closed = true;
        cancelTimeout();
        controller.close();
        if (state == State.LISTENING) set(State.CANCELLED, "Closed");
    }

    private void scheduleTimeout(int timeoutMs) {
        ScheduledFuture<?> task = timeoutExecutor.schedule(() -> {
            synchronized (VoiceListenSession.this) {
                if (state != State.LISTENING) return;
                state = State.TIMEOUT;
                controller.stop();
                listener.changed(State.TIMEOUT, "Listen timed out");
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
        timeout.set(task);
    }

    private void cancelTimeout() {
        ScheduledFuture<?> task = timeout.getAndSet(null);
        if (task != null) task.cancel(false);
    }

    private void onRecording(RecordingController.State recState, String message, File completed) {
        synchronized (this) {
            if (closed) {
                if (completed != null) completed.delete();
                return;
            }
            if (recState == RecordingController.State.RECORDING) {
                if (state != State.TIMEOUT && state != State.CANCELLED) set(State.LISTENING, "Listening…");
                return;
            }
            if (recState == RecordingController.State.STOPPING) {
                if (state == State.LISTENING) listener.changed(State.LISTENING, "Finishing listening…");
                return;
            }
            if (recState == RecordingController.State.ERROR) {
                cancelTimeout();
                if (completed != null) completed.delete();
                if (state != State.TIMEOUT && state != State.CANCELLED) set(State.ERROR, message);
                return;
            }
            if (recState == RecordingController.State.IDLE && completed != null && completed.isFile()) {
                cancelTimeout();
                if (state == State.TIMEOUT) {
                    completed.delete();
                    listener.changed(State.TIMEOUT, "Listen timed out");
                    state = State.IDLE;
                    return;
                }
                if (state == State.CANCELLED) {
                    completed.delete();
                    state = State.IDLE;
                    return;
                }
                state = State.READY;
                listener.wavReady(completed);
                listener.changed(State.READY, "Capture ready");
                state = State.IDLE;
                listener.changed(State.IDLE, "Idle");
                return;
            }
            if (recState == RecordingController.State.IDLE) {
                cancelTimeout();
                if (completed != null) completed.delete();
                if (state == State.TIMEOUT) {
                    listener.changed(State.TIMEOUT, "Listen timed out");
                    state = State.IDLE;
                    return;
                }
                if (state == State.LISTENING) set(State.CANCELLED, message);
            }
        }
    }

    private void set(State next, String message) {
        state = next;
        listener.changed(next, message);
    }
}