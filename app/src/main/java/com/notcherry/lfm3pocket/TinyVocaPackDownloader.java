package com.notcherry.lfm3pocket;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;

/** Downloads the English tiny-voca ONNX pack from Hugging Face on explicit user action. */
public final class TinyVocaPackDownloader {
    public interface Progress {
        void onFile(String path, long written, long total);
        void onDone();
        void onError(String message);
    }

    private final TinyVocaPackStore store;

    public TinyVocaPackDownloader(TinyVocaPackStore store) {
        this.store = store;
    }

    public void download(Progress progress) {
        try {
            for (Map.Entry<String, String> entry : TinyVocaPackManifest.expectedSha256().entrySet()) {
                String relative = entry.getKey();
                String expected = entry.getValue();
                File target = new File(store.root(), relative);
                fetchOne(relative, expected, target, progress);
            }
            store.markComplete();
            if (progress != null) progress.onDone();
        } catch (Exception failed) {
            store.clear();
            if (progress != null) progress.onError(failed.getMessage() == null ? failed.toString() : failed.getMessage());
        }
    }

    private static void fetchOne(String relative, String expectedSha256, File target, Progress progress) throws Exception {
        URL url = new URL(TinyVocaPackManifest.HF_BASE + relative);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(120_000);
        connection.connect();
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException(relative + ": HTTP " + code);
        }
        long total = connection.getContentLengthLong();
        target.getParentFile().mkdirs();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            long written = 0;
            for (;;) {
                int read = input.read(buffer);
                if (read < 0) break;
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
                written += read;
                if (progress != null) progress.onFile(relative, written, total);
            }
        } finally {
            connection.disconnect();
        }
        String actual = toHex(digest.digest());
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            target.delete();
            throw new IllegalStateException(relative + ": SHA-256 mismatch (expected " + expectedSha256 + ", got " + actual + ")");
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