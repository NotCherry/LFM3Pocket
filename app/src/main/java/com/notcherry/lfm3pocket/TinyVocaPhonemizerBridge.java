package com.notcherry.lfm3pocket;

import java.io.File;
import java.util.Objects;

/** JNI dizione (voca port) or JVM test shim. */
public final class TinyVocaPhonemizerBridge {
    private static final Object LOAD_LOCK = new Object();
    private static volatile boolean libraryLoaded;
    private static volatile boolean nativeReady;
    private static volatile File dataDir;

    private TinyVocaPhonemizerBridge() {}

    public static boolean isAvailable() {
        return nativeReady && dataDir != null && dataDir.isDirectory();
    }

    /** True when libhanami_dizione.so is packaged and loadable on this ABI. */
    public static boolean isNativeLibraryAvailable() {
        synchronized (LOAD_LOCK) {
            if (libraryLoaded) return true;
            try {
                System.loadLibrary("hanami_dizione");
                libraryLoaded = true;
                return true;
            } catch (Throwable unavailable) {
                return false;
            }
        }
    }

    /** Clears cached phonemizer readiness (e.g. after removing the pack). */
    public static void resetLoadedState() {
        synchronized (LOAD_LOCK) {
            nativeReady = false;
            dataDir = null;
        }
    }

    /** Load the native library (once) and initialize dizione for the given phonemizer directory. */
    public static boolean ensureLoaded(File phonemizerDir) {
        if (phonemizerDir == null || !phonemizerDir.isDirectory()) {
            return false;
        }
        synchronized (LOAD_LOCK) {
            if (isAvailable() && Objects.equals(dataDir.getAbsolutePath(), phonemizerDir.getAbsolutePath())) {
                return true;
            }
            return loadLocked(phonemizerDir);
        }
    }

    public static void load(File phonemizerDir) {
        synchronized (LOAD_LOCK) {
            loadLocked(phonemizerDir);
        }
    }

    private static boolean loadLocked(File phonemizerDir) {
        dataDir = phonemizerDir;
        if (!phonemizerDir.isDirectory()) {
            nativeReady = false;
            return false;
        }
        try {
            if (!libraryLoaded) {
                System.loadLibrary("hanami_dizione");
                libraryLoaded = true;
            }
            nativeReady = nativeInit(phonemizerDir.getAbsolutePath());
        } catch (Throwable unavailable) {
            nativeReady = false;
        }
        return nativeReady;
    }

    public static TinyVocaPhonemes phonemize(String text) {
        if (!isAvailable() || text == null || text.isBlank()) return null;
        long[][] data = nativePhonemize(text);
        if (data == null || data.length != 2) return null;
        return new TinyVocaPhonemes(toLong(data[0]), toLong(data[1]), text);
    }

    private static long[] toLong(long[] values) {
        return values == null ? new long[0] : values;
    }

    private static native boolean nativeInit(String dataDir);

    /** int64[2][]: ids, stress */
    private static native long[][] nativePhonemize(String text);
}