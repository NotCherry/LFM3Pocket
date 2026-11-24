package com.notcherry.lfm3pocket;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Managed download task for the optional English tiny-voca TTS pack. */
public final class TinyVocaPackDownloadTask implements ModelDownloadTask {
    static final long EXPECTED_BYTES = 28_000_000L;

    private final TinyVocaPackStore store;
    private final TinyVocaPackDownloader downloader;

    public TinyVocaPackDownloadTask(Context context) {
        this(new TinyVocaPackStore(context));
    }

    TinyVocaPackDownloadTask(TinyVocaPackStore store) {
        this(store, new TinyVocaPackDownloader(store));
    }

    TinyVocaPackDownloadTask(TinyVocaPackStore store, TinyVocaPackDownloader downloader) {
        this.store = store;
        this.downloader = downloader;
    }

    @Override public ModelDownloadKind kind() { return ModelDownloadKind.TINY_VOCA; }

    @Override public long expectedBytes() { return EXPECTED_BYTES; }

    @Override public String progressFileName() { return "tiny-voca"; }

    @Override public File partialFile() { return store.stagingDir(); }

    @Override public void discardPartialOnly() { store.discardStaging(); }

    @Override public void execute(CactusWhistleModelDownloader.Progress progress, ModelDownloadCancelToken cancel)
            throws Exception {
        File staging = store.stagingDir();
        store.discardStaging();
        staging.mkdirs();
        downloader.downloadToStaging(staging, progress, cancel);
        if (cancel != null) cancel.throwIfCancelled();
        promoteStagingToRoot(staging, store.root());
        store.markComplete();
        store.discardStaging();
        TinyVocaPhonemizerBridge.ensureLoaded(store.phonemizerDir());
        File model = store.onnxModel();
        if (progress != null) progress.onDone(model);
        notifyUiDone(model);
    }

    static void promoteStagingToRoot(File stagingDir, File rootDir) throws IOException {
        rootDir.mkdirs();
        for (String relative : TinyVocaPackManifest.expectedSha256().keySet()) {
            File src = new File(stagingDir, relative);
            if (!src.isFile()) {
                throw new IOException("Missing staged file: " + relative);
            }
            File dst = new File(rootDir, relative);
            File parent = dst.getParentFile();
            if (parent != null) parent.mkdirs();
            try {
                Files.move(src.toPath(), dst.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException fallback) {
                Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    @Override public void notifyUiDone(File modelFile) {
        TinyVocaPackDownloader.notifyUiDone();
    }

    @Override public void notifyUiError(String message) {
        TinyVocaPackDownloader.notifyUiError(message);
    }

    @Override public void notifyUiCancelled() {
        TinyVocaPackDownloader.notifyUiCancelled();
    }

    @Override public void notifyUiProgress(String name, long written, long total) {
        TinyVocaPackDownloader.notifyUiProgress(name, written, total);
    }
}
