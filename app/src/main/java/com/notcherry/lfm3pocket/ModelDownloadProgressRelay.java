package com.notcherry.lfm3pocket;

/** Forwards throttled download progress to foreground notifications. */
public final class ModelDownloadProgressRelay {
    public interface Listener {
        void onProgress(ModelDownloadKind kind, String label, int percent);
    }

    private static volatile Listener listener;

    private ModelDownloadProgressRelay() {}

    static void setListener(Listener value) {
        listener = value;
    }

    static void onProgress(ModelDownloadKind kind, String name, long written, long total) {
        Listener target = listener;
        if (target == null) return;
        String label = CactusWhistleModelDownloader.formatProgressLabel(name, written, total);
        int percent = total > 0 ? (int) Math.min(100L, (written * 100L) / total) : -1;
        target.onProgress(kind, label, percent);
    }
}