package com.notcherry.lfm3pocket;

import android.content.Context;

import java.io.File;

/** Per-model download contract executed by {@link ModelDownloadCoordinator}. */
public interface ModelDownloadTask {
    ModelDownloadKind kind();

    long expectedBytes();

    String progressFileName();

    File partialFile();

    void discardPartialOnly();

    /**
     * Runs the download into {@link #partialFile()}, verifies integrity, atomically publishes the
     * model, and selects it in preferences when appropriate.
     */
    void execute(CactusWhistleModelDownloader.Progress progress, ModelDownloadCancelToken cancel)
            throws Exception;

    void notifyUiDone(File modelFile);

    void notifyUiError(String message);

    void notifyUiCancelled();

    void notifyUiProgress(String name, long written, long total);
}