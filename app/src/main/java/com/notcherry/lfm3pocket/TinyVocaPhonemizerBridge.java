package com.notcherry.lfm3pocket;

import java.io.File;

/** JNI dizione (voca port) or JVM test shim. */
public final class TinyVocaPhonemizerBridge {
    static volatile boolean nativeLoaded;
    static volatile File dataDir;

    private TinyVocaPhonemizerBridge() {}

    public static boolean isAvailable() {
        return nativeLoaded && dataDir != null && dataDir.isDirectory();
    }

    public static void load(File phonemizerDir) {
        dataDir = phonemizerDir;
        if (phonemizerDir == null || !phonemizerDir.isDirectory()) {
            nativeLoaded = false;
            return;
        }
        try {
            System.loadLibrary("hanami_dizione");
            nativeLoaded = nativeInit(phonemizerDir.getAbsolutePath());
        } catch (Throwable unavailable) {
            nativeLoaded = false;
        }
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

    /** JVM-only seam for unit tests (no dizione). */
    static volatile PhonemizerTestHook testHook;

    interface PhonemizerTestHook {
        TinyVocaPhonemes phonemize(String text);
    }

    static TinyVocaPhonemes phonemizeForTest(String text) {
        if (testHook != null) return testHook.phonemize(text);
        return phonemize(text);
    }
}