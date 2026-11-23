package com.notcherry.lfm3pocket;

import android.content.Context;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Whistle download task wired to {@link CactusWhistleModelStore} and manifest pins. */
public final class CactusWhistleModelDownloadTask implements ModelDownloadTask {
    static final long EXPECTED_BYTES = 17_700_000L;

    private final CactusWhistleModelStore store;
    private final CactusWhistleModelDownloader downloader;

    public CactusWhistleModelDownloadTask(Context context) {
        this(new CactusWhistleModelStore(context), new CactusWhistleModelDownloader(new CactusWhistleModelStore(context)));
    }

    CactusWhistleModelDownloadTask(CactusWhistleModelStore store, CactusWhistleModelDownloader downloader) {
        this.store = store;
        this.downloader = downloader;
    }

    @Override public ModelDownloadKind kind() { return ModelDownloadKind.CACTUS_WHISTLE; }

    @Override public long expectedBytes() { return EXPECTED_BYTES; }

    @Override public String progressFileName() { return CactusWhistleModelManifest.FILE_NAME; }

    @Override public File partialFile() { return store.partialFile(); }

    @Override public void discardPartialOnly() { store.discardPartial(); }

    @Override public void execute(CactusWhistleModelDownloader.Progress progress, ModelDownloadCancelToken cancel)
            throws Exception {
        File target = store.modelFile();
        File temp = store.partialFile();
        store.directory().mkdirs();
        downloader.fetchToPartial(temp, progress, cancel);
        cancel.throwIfCancelled();
        Files.move(temp.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        store.markComplete();
        store.persistDownloadedModelSelection();
        if (progress != null) progress.onDone(target);
        notifyUiDone(target);
    }

    @Override public void notifyUiDone(File modelFile) {
        CactusWhistleModelDownloader.notifyUiDone(modelFile);
    }

    @Override public void notifyUiError(String message) {
        CactusWhistleModelDownloader.notifyUiError(message);
    }

    @Override public void notifyUiProgress(String name, long written, long total) {
        CactusWhistleModelDownloader.notifyUiProgress(name, written, total);
    }
}