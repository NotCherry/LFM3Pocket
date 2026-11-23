package com.notcherry.lfm3pocket;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Process-wide single-flight execution and cancel for model downloads. */
public final class ModelDownloadCoordinator {
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    private static final AtomicReference<ModelDownloadKind> ACTIVE = new AtomicReference<>();
    private static final AtomicReference<ModelDownloadCancelToken> CANCEL = new AtomicReference<>();
    private static final ConcurrentHashMap<ModelDownloadKind, Boolean> QUEUED = new ConcurrentHashMap<>();

    private ModelDownloadCoordinator() {}

    public static boolean isInFlight() {
        return IN_FLIGHT.get();
    }

    public static boolean isQueued(ModelDownloadKind kind) {
        return Boolean.TRUE.equals(QUEUED.get(kind));
    }

    public static boolean isActiveOrQueued(ModelDownloadKind kind) {
        return isQueued(kind) || isDownloading(kind);
    }

    public static ModelDownloadKind activeKind() {
        return ACTIVE.get();
    }

    public static boolean isDownloading(ModelDownloadKind kind) {
        return IN_FLIGHT.get() && kind == ACTIVE.get();
    }

    static void resetForTests() {
        IN_FLIGHT.set(false);
        ACTIVE.set(null);
        CANCEL.set(null);
        QUEUED.clear();
    }

    public static void markQueued(ModelDownloadKind kind) {
        QUEUED.put(kind, Boolean.TRUE);
    }

    public static void clearQueued(ModelDownloadKind kind) {
        QUEUED.remove(kind);
    }

    /** Returns false when another download is already running. */
    public static boolean tryBegin(ModelDownloadKind kind, ModelDownloadCancelToken cancel) {
        if (!IN_FLIGHT.compareAndSet(false, true)) return false;
        ACTIVE.set(kind);
        CANCEL.set(cancel);
        return true;
    }

    public static void requestUserCancel(ModelDownloadKind kind) {
        ModelDownloadCancelToken token = CANCEL.get();
        if (token != null && kind == ACTIVE.get()) {
            token.requestUserCancel();
        }
    }

    public static void requestSystemStop(ModelDownloadKind kind) {
        ModelDownloadCancelToken token = CANCEL.get();
        if (token != null && kind == ACTIVE.get()) {
            token.requestSystemStop();
        }
    }

    /** Synchronous run used by the worker and JVM tests. */
    public static Result run(ModelDownloadTask task, ModelDownloadCancelToken cancel) {
        clearQueued(task.kind());
        if (!tryBegin(task.kind(), cancel)) {
            return Result.ALREADY_RUNNING;
        }
        try {
            CactusWhistleModelDownloader.Progress progress = progressRelay(task);
            task.execute(progress, cancel);
            return Result.SUCCESS;
        } catch (ModelDownloadCancelledException cancelled) {
            return outcomeForCancel(task, cancel);
        } catch (Exception failed) {
            if (cancel.isCancelled()) {
                return outcomeForCancel(task, cancel);
            }
            task.discardPartialOnly();
            String message = failed.getMessage() == null ? failed.toString() : failed.getMessage();
            task.notifyUiError(message);
            return Result.FAILED;
        } finally {
            IN_FLIGHT.set(false);
            ACTIVE.set(null);
            CANCEL.set(null);
            clearQueued(task.kind());
        }
    }

    private static Result outcomeForCancel(ModelDownloadTask task, ModelDownloadCancelToken cancel) {
        if (cancel.isUserCancel()) {
            task.discardPartialOnly();
            task.notifyUiCancelled();
            return Result.CANCELLED;
        }
        return Result.STOPPED;
    }

    private static CactusWhistleModelDownloader.Progress progressRelay(ModelDownloadTask task) {
        return new CactusWhistleModelDownloader.Progress() {
            @Override public void onFile(String name, long written, long total) {
                task.notifyUiProgress(name, written, total);
                ModelDownloadProgressRelay.onProgress(task.kind(), name, written, total);
            }
            @Override public void onDone(File modelFile) { }
            @Override public void onError(String message) { }
        };
    }

    public enum Result {
        SUCCESS,
        FAILED,
        CANCELLED,
        STOPPED,
        ALREADY_RUNNING
    }
}