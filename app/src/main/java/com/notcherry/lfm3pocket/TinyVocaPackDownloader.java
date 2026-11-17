package com.notcherry.lfm3pocket;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

/** Downloads the English tiny-voca ONNX pack from Hugging Face on explicit user action. */
public final class TinyVocaPackDownloader {
    public interface Progress {
        void onFile(String path, long written, long total);
        void onDone();
        void onError(String message);
    }

    private static final List<String> PATHS = Arrays.asList(
            "config.json",
            "onnx/model.onnx",
            "phonemizer/lexicon.tsv",
            "phonemizer/g2p.npz",
            "phonemizer/g2p.json",
            "phonemizer/align.json",
            "phonemizer/homographs.json",
            "phonemizer/lexicon.json",
            "phonemizer/sources.json");

    private final TinyVocaPackStore store;

    public TinyVocaPackDownloader(TinyVocaPackStore store) {
        this.store = store;
    }

    public void download(Progress progress) {
        try {
            for (String relative : PATHS) {
                File target = new File(store.root(), relative);
                fetchOne(relative, target, progress);
            }
            store.markComplete();
            if (progress != null) progress.onDone();
        } catch (Exception failed) {
            store.clear();
            if (progress != null) progress.onError(failed.getMessage() == null ? failed.toString() : failed.getMessage());
        }
    }

    private static void fetchOne(String relative, File target, Progress progress) throws Exception {
        URL url = new URL(TinyVocaPackStore.HF_BASE + relative);
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
        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            long written = 0;
            for (;;) {
                int read = input.read(buffer);
                if (read < 0) break;
                output.write(buffer, 0, read);
                written += read;
                if (progress != null) progress.onFile(relative, written, total);
            }
        } finally {
            connection.disconnect();
        }
    }
}