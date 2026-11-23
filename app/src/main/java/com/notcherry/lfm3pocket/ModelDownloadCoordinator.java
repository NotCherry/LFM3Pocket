package com.notcherry.lfm3pocket;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Process-wide single-flight execution and cancel for model downloads. */
public final class ModelDownloadCoordinator {
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    private static final AtomicReference<ModelDownloadKind> ACTIVE = new AtomicReference<>();
    private static final AtomicReference<ModelDownloadCancelToken> CANCEL = new AtomicReference<>();

    private ModelDownloadCoordinator() {}

    public static boolean isInFlight() {
        return IN_FLIGHT.get();
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
    }

    /** Returns false when another download is already running. */
    public static boolean tryBegin(ModelDownloadKind kind, ModelDownloadCancelToken cancel) {
        if (!IN_FLIGHT.compareAndSet(false, true)) return false;
        ACTIVE.set(kind);
        CANCEL.set(cancel);
        return true;
    }

    public static void requestCancel(ModelDownloadKind kind) {
        if (kind != ACTIVE.get()) return;
        ModelDownloadCancelToken token = CANCEL.get();
        if (token != null) token.requestCancel();
    }

    public static ModelDownloadCancelToken cancelToken() {
        return CANCEL.get();
    }

    /** Synchronous run used by the worker and JVM tests. */
    public static Result run(ModelDownloadTask task, ModelDownloadCancelToken cancel) {
        if (!tryBegin(task.kind(), cancel)) {
            return Result.ALREADY_RUNNING;
        }
        try {
            CactusWhistleModelDownloader.Progress progress = progressRelay(task);
            task.execute(progress, cancel);
            return Result.SUCCESS;
        } catch (ModelDownloadCancelledException cancelled) {
            task.discardPartialOnly();
            task.notifyUiError("cancelled");
            return Result.CANCELLED;
        } catch (Exception failed) {
            task.discardPartialOnly();
            String message = failed.getMessage() == null ? failed.toString() : failed.getMessage();
            task.notifyUiError(message);
            return Result.FAILED;
        } finally {
            IN_FLIGHT.set(false);
            ACTIVE.set(null);
            CANCEL.set(null);
        }
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
        ALREADY_RUNNING
    }
}