package com.notcherry.lfm3pocket;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** On-disk location for the optional in-app downloaded Cactus Whistle model (not shipped in APK). */
public final class CactusWhistleModelStore {
    private static final String TAG = "CactusWhistleModelStore";

    private final Context appContext;
    private final File dir;
    private final File modelFile;
    private final File marker;
    private final File revisionFile;

    public CactusWhistleModelStore(Context context) {
        this(context.getApplicationContext(), new File(context.getFilesDir(), "cactus"));
    }

    CactusWhistleModelStore(Context appContext, File dir) {
        this.appContext = appContext;
        this.dir = dir;
        modelFile = new File(dir, CactusWhistleModelManifest.FILE_NAME);
        marker = new File(dir, ".whistle-download-complete");
        revisionFile = new File(dir, ".whistle-download-revision");
    }

    /** Package-visible for JVM contract tests (no Android Context). */
    CactusWhistleModelStore(File dir) {
        appContext = null;
        this.dir = dir;
        modelFile = new File(dir, CactusWhistleModelManifest.FILE_NAME);
        marker = new File(dir, ".whistle-download-complete");
        revisionFile = new File(dir, ".whistle-download-revision");
    }

    File directory() { return dir; }

    public File modelFile() { return modelFile; }

    File partialFile() {
        return new File(dir, CactusWhistleModelManifest.FILE_NAME + ".partial");
    }

    /** Removes only a failed in-flight download, preserving any verified model on disk. */
    void discardPartial() {
        deleteFile(partialFile());
    }

    /** Records the verified in-app model path for speech STT (application context). */
    void persistDownloadedModelSelection() {
        if (appContext == null) return;
        new RuntimePreferences(appContext).setCactusWhistleModelPath(modelFile.getAbsolutePath());
    }

    public boolean isReady() {
        return marker.isFile()
                && CactusWhistleModelManifest.HF_REVISION.equals(storedRevision())
                && modelFile.isFile()
                && modelFile.length() > 1_000_000L;
    }

    public String storedRevision() {
        if (!revisionFile.isFile()) return "";
        try (BufferedReader reader = new BufferedReader(new FileReader(revisionFile, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (Exception failed) {
            return "";
        }
    }

    public void markComplete() throws IOException {
        dir.mkdirs();
        try (FileOutputStream revision = new FileOutputStream(revisionFile)) {
            revision.write(CactusWhistleModelManifest.HF_REVISION.getBytes(StandardCharsets.UTF_8));
            revision.flush();
        }
        if (!marker.exists() && !marker.createNewFile()) {
            throw new IOException("Could not create Whistle download marker");
        }
    }

    /** Deletes the managed in-app download artifacts without touching other user-provided files. */
    public void clear() {
        deleteFile(marker);
        deleteFile(revisionFile);
        deleteFile(modelFile);
    }

    private static void deleteFile(File file) {
        if (file == null || !file.exists() || file.isDirectory()) return;
        if (!file.delete()) Log.w(TAG, "Could not delete " + file.getAbsolutePath());
    }
}