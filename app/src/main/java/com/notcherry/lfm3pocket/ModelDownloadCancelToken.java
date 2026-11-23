package com.notcherry.lfm3pocket;

import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-safe cancel flag shared between UI, notification, and the download thread. */
public final class ModelDownloadCancelToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean userCancel = new AtomicBoolean();

    public void requestUserCancel() {
        userCancel.set(true);
        cancelled.set(true);
        fireInterruptHooks();
    }

    /** Stops the transfer without treating it as an explicit user cancel (keeps `.partial`). */
    public void requestSystemStop() {
        cancelled.set(true);
        fireInterruptHooks();
    }

    public boolean isUserCancel() {
        return userCancel.get();
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void throwIfCancelled() throws ModelDownloadCancelledException {
        if (cancelled.get()) throw new ModelDownloadCancelledException();
    }

    void resetForTests() {
        cancelled.set(false);
        userCancel.set(false);
        interruptHooks = null;
        activeConnection = null;
    }

    private volatile java.net.HttpURLConnection activeConnection;
    private Runnable interruptHooks;

    void bindHttpConnection(java.net.HttpURLConnection connection) {
        activeConnection = connection;
    }

    void setInterruptHookForTests(Runnable hook) {
        interruptHooks = hook;
    }

    private void fireInterruptHooks() {
        java.net.HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
        Runnable hook = interruptHooks;
        if (hook != null) hook.run();
    }
}