package com.notcherry.lfm3pocket;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;

/** On-disk layout for optional tiny-voca assets (not shipped in APK). */
public final class TinyVocaPackStore {
    public static final String HF_REPO = TinyVocaPackManifest.HF_REPO;
    public static final String HF_BASE = TinyVocaPackManifest.HF_BASE;

    private final File root;
    private final File marker;

    public TinyVocaPackStore(Context context) {
        root = new File(context.getFilesDir(), "tiny-voca");
        marker = new File(root, ".pack-complete");
    }

    File root() { return root; }

    File onnxModel() { return new File(root, "onnx/model.onnx"); }

    File phonemizerDir() { return new File(root, "phonemizer"); }

    File configFile() { return new File(root, "config.json"); }

    public boolean isReady() {
        return marker.isFile()
                && storedRevision().equals(TinyVocaPackManifest.HF_REVISION)
                && onnxModel().isFile() && onnxModel().length() > 1_000_000L
                && configFile().isFile()
                && phonemizerDir().isDirectory()
                && new File(phonemizerDir(), "lexicon.tsv").isFile()
                && new File(phonemizerDir(), "g2p.npz").isFile();
    }

    public String storedRevision() {
        File revision = new File(root, ".revision");
        if (!revision.isFile()) return "";
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new FileReader(revision, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (Exception failed) {
            return "";
        }
    }

    public JSONObject readConfig() {
        if (!configFile().isFile()) return new JSONObject();
        try (BufferedReader reader = new BufferedReader(new FileReader(configFile(), StandardCharsets.UTF_8))) {
            StringBuilder json = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) json.append(line).append('\n');
            return new JSONObject(json.toString());
        } catch (Exception failed) {
            return new JSONObject();
        }
    }

    public void markComplete() {
        root.mkdirs();
        try (FileOutputStream revision = new FileOutputStream(new File(root, ".revision"))) {
            revision.write(TinyVocaPackManifest.HF_REVISION.getBytes(StandardCharsets.UTF_8));
            if (!marker.exists()) marker.createNewFile();
        } catch (Exception ignored) { }
    }

    public void clear() {
        deleteTree(root);
    }

    static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] kids = file.listFiles();
            if (kids != null) for (File kid : kids) deleteTree(kid);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

}