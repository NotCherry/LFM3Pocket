package com.notcherry.lfm3pocket;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Downloads the pinned Cactus Whistle model from Hugging Face on explicit user action. */
public final class CactusWhistleModelDownloader {
    static final long PROGRESS_INTERVAL_MS = 250L;

    public interface Progress {
        void onFile(String name, long written, long total);
        void onDone(File modelFile);
        void onError(String message);
    }

    /** UI callbacks for an active Activity; not held across recreation beyond the current instance. */
    public interface UiCallbacks {
        void onProgress(String name, long written, long total);
        void onDone(File modelFile);
        void onError(String message);
    }

    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    private static volatile UiCallbacks boundUi;
    private static volatile String pendingBackgroundError;

    private final CactusWhistleModelStore store;
    private final ModelFetcher fetcher;
    private final LongSupplier clock;

    public CactusWhistleModelDownloader(CactusWhistleModelStore store) {
        this(store, CactusWhistleModelDownloader::fetchFromHuggingFace, System::currentTimeMillis);
    }

    CactusWhistleModelDownloader(CactusWhistleModelStore store, ModelFetcher fetcher, LongSupplier clock) {
        this.store = store;
        this.fetcher = fetcher;
        this.clock = clock;
    }

    public static boolean isDownloading() {
        return IN_FLIGHT.get();
    }

    static void resetInFlightForTests() {
        IN_FLIGHT.set(false);
    }

    static void clearBoundUiForTests() {
        boundUi = null;
    }

    public static void bindUi(UiCallbacks callbacks) {
        boundUi = callbacks;
    }

    public static void unbindUi(UiCallbacks callbacks) {
        if (boundUi == callbacks) boundUi = null;
    }

    public static String consumePendingBackgroundError() {
        String message = pendingBackgroundError;
        pendingBackgroundError = null;
        return message;
    }

    static void resetPendingBackgroundErrorForTests() {
        pendingBackgroundError = null;
    }

    /** Starts a background download, or returns false if one is already in flight. */
    public boolean startDownload(Progress progress) {
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            return false;
        }
        new Thread(() -> {
            try {
                downloadBody(progress);
            } finally {
                IN_FLIGHT.set(false);
            }
        }, "cactus-whistle-download").start();
        return true;
    }

    /** Synchronous download for JVM tests; returns false if already in flight. */
    boolean download(Progress progress) {
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            return false;
        }
        try {
            downloadBody(progress);
            return true;
        } finally {
            IN_FLIGHT.set(false);
        }
    }

    private void downloadBody(Progress progress) {
        File target = store.modelFile();
        File temp = store.partialFile();
        try {
            store.directory().mkdirs();
            fetcher.fetch(CactusWhistleModelManifest.FILE_NAME, CactusWhistleModelManifest.SHA256, temp,
                    wrapProgress(progress), clock);
            Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            store.markComplete();
            store.persistDownloadedModelSelection();
            if (progress != null) progress.onDone(target);
            UiCallbacks ui = boundUi;
            if (ui != null) {
                ui.onDone(target);
            } else {
                pendingBackgroundError = null;
            }
        } catch (Exception failed) {
            store.discardPartial();
            String message = failed.getMessage() == null ? failed.toString() : failed.getMessage();
            if (progress != null) progress.onError(message);
            UiCallbacks ui = boundUi;
            if (ui != null) {
                ui.onError(message);
            } else {
                pendingBackgroundError = message;
            }
        }
    }

    static String formatProgressLabel(String name, long written, long total) {
        if (total > 0) {
            int percent = (int) Math.min(100L, (written * 100L) / total);
            return name + " · " + percent + "%";
        }
        return name + " · " + written + " B";
    }

    private Progress wrapProgress(Progress progress) {
        return new Progress() {
            @Override public void onFile(String name, long written, long total) {
                if (progress != null) progress.onFile(name, written, total);
                UiCallbacks ui = boundUi;
                if (ui != null) ui.onProgress(name, written, total);
            }
            @Override public void onDone(File modelFile) { }
            @Override public void onError(String message) { }
        };
    }

    interface ModelFetcher {
        void fetch(String name, String expectedSha256, File target, Progress progress, LongSupplier clock)
                throws Exception;
    }

    private static void fetchFromHuggingFace(String name, String expectedSha256, File target,
                                             Progress progress, LongSupplier clock) throws Exception {
        URL url = new URL(CactusWhistleModelManifest.HF_BASE + name);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(120_000);
        try {
            connection.connect();
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException(name + ": HTTP " + code);
            }
            long total = connection.getContentLengthLong();
            File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();
            try (InputStream input = connection.getInputStream()) {
                writeAndVerifySha256(input, expectedSha256, name, target, total, progress, clock);
            }
        } finally {
            connection.disconnect();
        }
    }

    static void writeAndVerifySha256(InputStream input, String expectedSha256, String name, File target,
                                     long total, Progress progress, LongSupplier clock) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            long written = 0;
            long lastProgressMs = Long.MIN_VALUE;
            for (;;) {
                int read = input.read(buffer);
                if (read < 0) break;
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
                written += read;
                if (progress != null) {
                    long now = clock.getAsLong();
                    if (lastProgressMs == Long.MIN_VALUE || now - lastProgressMs >= PROGRESS_INTERVAL_MS) {
                        progress.onFile(name, written, total);
                        lastProgressMs = now;
                    }
                }
            }
            if (progress != null) progress.onFile(name, written, total);
        }
        String actual = toHex(digest.digest());
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw new IllegalStateException(name + ": SHA-256 mismatch (expected " + expectedSha256 + ", got " + actual + ")");
        }
    }

    private static String toHex(byte[] hash) {
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            hex.append(String.format(Locale.US, "%02x", value));
        }
        return hex.toString();
    }
}