package com.notcherry.lfm3pocket;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Downloads the English tiny-voca ONNX pack from Hugging Face on explicit user action. */
public final class TinyVocaPackDownloader {
    public interface Progress {
        void onFile(String path, long written, long total);
        void onDone();
        void onError(String message);
    }

    /** UI callbacks for an active Activity; not held across recreation beyond the current instance. */
    public interface UiCallbacks {
        void onProgress(String name, long written, long total);
        void onDone();
        void onError(String message);
        void onCancelled();
    }

    interface FileFetcher {
        void fetch(String relative, String expectedSha256, String displayName, File target,
                   CactusWhistleModelDownloader.Progress progress, LongSupplier clock,
                   ModelDownloadCancelToken cancel) throws Exception;
    }

    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    private static volatile UiCallbacks boundUi;
    private static volatile String pendingBackgroundError;

    private final TinyVocaPackStore store;
    private final FileFetcher fetcher;
    private final LongSupplier clock;

    public TinyVocaPackDownloader(TinyVocaPackStore store) {
        this(store, TinyVocaPackDownloader::fetchFromHuggingFace, System::currentTimeMillis);
    }

    TinyVocaPackDownloader(TinyVocaPackStore store, FileFetcher fetcher, LongSupplier clock) {
        this.store = store;
        this.fetcher = fetcher;
        this.clock = clock;
    }

    public static boolean isDownloading() {
        return IN_FLIGHT.get()
                || ModelDownloadCoordinator.isActiveOrQueued(ModelDownloadKind.TINY_VOCA);
    }

    public static void bindUi(UiCallbacks callbacks) {
        boundUi = callbacks;
    }

    public static void unbindUi(UiCallbacks callbacks) {
        if (boundUi == callbacks) boundUi = null;
    }

    static void clearBoundUiForTests() {
        boundUi = null;
    }

    static void resetInFlightForTests() {
        IN_FLIGHT.set(false);
    }

    static void resetPendingBackgroundErrorForTests() {
        pendingBackgroundError = null;
    }

    static void notifyUiProgress(String name, long written, long total) {
        UiCallbacks ui = boundUi;
        if (ui != null) ui.onProgress(name, written, total);
    }

    static void notifyUiDone() {
        UiCallbacks ui = boundUi;
        if (ui != null) {
            ui.onDone();
        } else {
            pendingBackgroundError = null;
        }
    }

    static void notifyUiError(String message) {
        UiCallbacks ui = boundUi;
        if (ui != null) {
            ui.onError(message);
        } else {
            pendingBackgroundError = message;
        }
    }

    static void notifyUiCancelled() {
        UiCallbacks ui = boundUi;
        if (ui != null) {
            ui.onCancelled();
        }
        pendingBackgroundError = null;
    }

    public static String consumePendingBackgroundError() {
        String message = pendingBackgroundError;
        pendingBackgroundError = null;
        return message;
    }

    /** Downloads and verifies all pinned pack files into {@code stagingDir}. */
    void downloadToStaging(File stagingDir, CactusWhistleModelDownloader.Progress progress,
                           ModelDownloadCancelToken cancel) throws Exception {
        Map<String, String> entries = TinyVocaPackManifest.expectedSha256();
        int totalFiles = entries.size();
        int index = 0;
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            if (cancel != null) cancel.throwIfCancelled();
            index++;
            String relative = entry.getKey();
            String expected = entry.getValue();
            String shortName = relative.contains("/")
                    ? relative.substring(relative.lastIndexOf('/') + 1) : relative;
            String displayName = "(" + index + "/" + totalFiles + ") " + shortName;
            File target = new File(stagingDir, relative);
            File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();
            fetcher.fetch(relative, expected, displayName, target, progress, clock, cancel);
        }
    }

    /** Synchronous download helper kept for direct callers/tests. */
    public void download(Progress progress) {
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            if (progress != null) progress.onError("tiny-voca download already in progress");
            return;
        }
        try {
            File staging = store.stagingDir();
            store.discardStaging();
            CactusWhistleModelDownloader.Progress adapter = progress == null ? null
                    : new CactusWhistleModelDownloader.Progress() {
                @Override public void onFile(String name, long written, long total) {
                    progress.onFile(name, written, total);
                }
                @Override public void onDone(File modelFile) { }
                @Override public void onError(String message) { }
            };
            downloadToStaging(staging, adapter, null);
            TinyVocaPackDownloadTask.promoteStagingToRoot(staging, store.root());
            store.markComplete();
            store.discardStaging();
            if (progress != null) progress.onDone();
        } catch (Exception failed) {
            store.discardStaging();
            if (progress != null) {
                progress.onError(failed.getMessage() == null ? failed.toString() : failed.getMessage());
            }
        } finally {
            IN_FLIGHT.set(false);
        }
    }

    private static void fetchFromHuggingFace(String relative, String expectedSha256, String displayName,
                                             File target, CactusWhistleModelDownloader.Progress progress,
                                             LongSupplier clock, ModelDownloadCancelToken cancel) throws Exception {
        URL url = new URL(TinyVocaPackManifest.HF_BASE + relative);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(120_000);
        try {
            if (cancel != null) cancel.bindHttpConnection(connection);
            connection.connect();
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException(relative + ": HTTP " + code);
            }
            long total = connection.getContentLengthLong();
            File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();
            try (InputStream input = connection.getInputStream()) {
                CactusWhistleModelDownloader.writeAndVerifySha256(
                        input, expectedSha256, displayName, target, total, progress, clock, cancel);
            }
        } finally {
            connection.disconnect();
        }
    }
}