package com.notcherry.lfm3pocket;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JNI bridge to the upstream Cactus runtime (Whistle STT). The native library is optional:
 * when the Cactus runtime artifacts are not linked/available, calls report unavailable.
 */
public final class CactusWhistleBridge {
    private static final Object LOCK = new Object();
    private static volatile boolean loadedAttempted;
    private static volatile boolean nativeAvailable;
    private static volatile String loadedModelPath = "";
    private static volatile boolean modelLoaded;

    private CactusWhistleBridge() {}

    public static boolean isNativeAvailable() {
        ensureLoaded();
        return nativeAvailable;
    }

    public static synchronized SttAvailability availability(String modelPath) {
        ensureLoaded();
        if (!nativeAvailable) {
            return SttAvailability.unavailable("Cactus runtime is not available in this build");
        }
        File model = modelPath == null ? null : new File(modelPath.trim());
        if (model == null || modelPath.trim().isEmpty()) {
            return SttAvailability.unavailable("Select a Cactus Whistle model (.cact)");
        }
        if (!model.isFile() || model.length() <= 0L) {
            return SttAvailability.unavailable("Cactus Whistle model is missing or unreadable");
        }
        return SttAvailability.ready();
    }

    public static synchronized SttResult transcribe(String modelPath, float[] pcm16kMono, AtomicBoolean cancelled) {
        ensureLoaded();
        if (!nativeAvailable) {
            return SttResult.unavailable("Cactus runtime is not available in this build");
        }
        if (cancelled != null && cancelled.get()) return SttResult.cancelled();
        SttAvailability avail = availability(modelPath);
        if (!avail.available) return SttResult.unavailable(avail.message);
        if (pcm16kMono == null || pcm16kMono.length == 0) {
            return SttResult.empty("No audio captured");
        }
        synchronized (LOCK) {
            if (cancelled != null && cancelled.get()) return SttResult.cancelled();
            String path = modelPath.trim();
            if (!modelLoaded || !path.equals(loadedModelPath)) {
                String loadError = loadModel(path);
                if (loadError != null) {
                    modelLoaded = false;
                    loadedModelPath = "";
                    return SttResult.error(loadError);
                }
                modelLoaded = true;
                loadedModelPath = path;
            }
            if (cancelled != null && cancelled.get()) return SttResult.cancelled();
            String jsonOrText = nativeTranscribe(pcm16kMono, pcm16kMono.length, null, null, false);
            if (cancelled != null && cancelled.get()) return SttResult.cancelled();
            if (jsonOrText == null) {
                String err = nativeLastError();
                if (err == null || err.trim().isEmpty()) err = "Cactus transcription failed";
                return SttResult.error(err);
            }
            String text = extractText(jsonOrText);
            if (text == null || text.trim().isEmpty()) {
                return SttResult.empty("No speech detected");
            }
            return SttResult.ok(text);
        }
    }

    private static String loadModel(String path) {
        try {
            byte[] bytes = readAll(new File(path));
            if (bytes.length == 0) return "Cactus Whistle model is empty";
            int rc = nativeLoad(bytes);
            if (rc < 0) {
                String err = nativeLastError();
                return err == null || err.trim().isEmpty() ? "Could not load Cactus Whistle model" : err;
            }
            nativeInit("", "", null);
            return null;
        } catch (IOException e) {
            return "Could not read Cactus Whistle model: " + e.getMessage();
        } catch (UnsatisfiedLinkError e) {
            nativeAvailable = false;
            return "Cactus runtime is not available in this build";
        } catch (Throwable t) {
            return "Could not load Cactus Whistle model: " + t.getMessage();
        }
    }

    private static void ensureLoaded() {
        if (loadedAttempted) return;
        synchronized (CactusWhistleBridge.class) {
            if (loadedAttempted) return;
            loadedAttempted = true;
            try {
                // Implemented in liblfm3pocket when built with HANAMI_CACTUS_WHISTLE.
                nativeAvailable = nativeProbe();
            } catch (UnsatisfiedLinkError | Throwable ignored) {
                nativeAvailable = false;
            }
        }
    }

    static String extractText(String jsonOrText) {
        if (jsonOrText == null) return "";
        String trimmed = jsonOrText.trim();
        if (trimmed.startsWith("{")) {
            try {
                org.json.JSONObject obj = new org.json.JSONObject(trimmed);
                String text = obj.optString("text", "");
                return text == null ? "" : text.trim();
            } catch (Exception ignored) {
                // Fall through to raw text.
            }
        }
        return trimmed;
    }

    private static byte[] readAll(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.max(0, file.length())];
            int offset = 0;
            while (offset < data.length) {
                int read = in.read(data, offset, data.length - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset == data.length) return data;
            byte[] out = new byte[offset];
            System.arraycopy(data, 0, out, 0, offset);
            return out;
        }
    }

    private static native boolean nativeProbe();
    private static native int nativeLoad(byte[] modelBytes);
    private static native int nativeInit(String systemPrompt, String toolsJson, String toolIndexPath);
    private static native String nativeLastError();
    private static native String nativeTranscribe(float[] pcm, int samples, String language, String keywords, boolean wordTimestamps);
}
