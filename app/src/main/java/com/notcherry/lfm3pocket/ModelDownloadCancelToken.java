package com.notcherry.lfm3pocket;

import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-safe cancel flag shared between UI, notification, and the download thread. */
public final class ModelDownloadCancelToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public void requestCancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void throwIfCancelled() throws ModelDownloadCancelledException {
        if (cancelled.get()) throw new ModelDownloadCancelledException();
    }

    void resetForTests() {
        cancelled.set(false);
    }
}