package com.notcherry.lfm3pocket;

/** Thrown when the user cancels an in-flight model download. */
public final class ModelDownloadCancelledException extends Exception {
    ModelDownloadCancelledException() {
        super("cancelled");
    }
}