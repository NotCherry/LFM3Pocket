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

/** Downloads the pinned Cactus Whistle model from Hugging Face on explicit user action. */
public final class CactusWhistleModelDownloader {
    public interface Progress {
        void onFile(String name, long written, long total);
        void onDone(File modelFile);
        void onError(String message);
    }

    private final CactusWhistleModelStore store;

    public CactusWhistleModelDownloader(CactusWhistleModelStore store) {
        this.store = store;
    }

    public void download(Progress progress) {
        File target = store.modelFile();
        File temp = new File(store.directory(), CactusWhistleModelManifest.FILE_NAME + ".partial");
        try {
            store.directory().mkdirs();
            fetch(CactusWhistleModelManifest.FILE_NAME, CactusWhistleModelManifest.SHA256, temp, progress);
            Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            store.markComplete();
            if (progress != null) progress.onDone(target);
        } catch (Exception failed) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            store.clear();
            if (progress != null) {
                progress.onError(failed.getMessage() == null ? failed.toString() : failed.getMessage());
            }
        }
    }

    private static void fetch(String name, String expectedSha256, File target, Progress progress) throws Exception {
        URL url = new URL(CactusWhistleModelManifest.HF_BASE + name);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(120_000);
        connection.connect();
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException(name + ": HTTP " + code);
        }
        long total = connection.getContentLengthLong();
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
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
                if (progress != null) progress.onFile(name, written, total);
            }
        } finally {
            connection.disconnect();
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
