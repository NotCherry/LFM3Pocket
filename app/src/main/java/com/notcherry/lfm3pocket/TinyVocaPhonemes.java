package com.notcherry.lfm3pocket;

/** Phoneme token ids + stress parallel array for tiny-voca ONNX inputs. */
public final class TinyVocaPhonemes {
    public final long[] ids;
    public final long[] stress;
    public final String debug;

    public TinyVocaPhonemes(long[] ids, long[] stress, String debug) {
        this.ids = ids;
        this.stress = stress;
        this.debug = debug == null ? "" : debug;
    }
}