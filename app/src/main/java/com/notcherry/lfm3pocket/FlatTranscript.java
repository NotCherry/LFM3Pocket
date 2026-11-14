package com.notcherry.lfm3pocket;

/**
 * Java façade over {@link FlatTranscriptFormatter} for {@link ChatSnapshot} callers (U04/U05).
 * Canonical formatting rules live in the Kotlin formatter; this class only adapts the snapshot.
 */
public final class FlatTranscript {
    private FlatTranscript() {}

    public static String format(ChatSnapshot snapshot) {
        return FlatTranscriptFormatter.formatSnapshot(snapshot);
    }
}
