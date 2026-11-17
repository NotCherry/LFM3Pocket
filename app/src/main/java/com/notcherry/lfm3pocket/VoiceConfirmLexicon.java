package com.notcherry.lfm3pocket;

import java.util.Locale;

/** Closed Polish + English yes/no lexicon for voice confirmation (no model judgment). */
public final class VoiceConfirmLexicon {
    public enum Decision { APPROVE, REJECT, AMBIGUOUS }

    private static final String[] APPROVE = {
            "tak", "tak.", "potwierdzam", "yes", "yep", "confirm"
    };
    private static final String[] REJECT = {
            "nie", "nie.", "anuluj", "no", "cancel", "nope"
    };

    private VoiceConfirmLexicon() {}

    public static Decision matchTranscript(String raw) {
        if (raw == null) return Decision.AMBIGUOUS;
        String normalized = normalize(raw);
        if (normalized.isEmpty()) return Decision.AMBIGUOUS;
        boolean approve = containsToken(normalized, APPROVE);
        boolean reject = containsToken(normalized, REJECT);
        if (approve && !reject) return Decision.APPROVE;
        if (reject && !approve) return Decision.REJECT;
        return Decision.AMBIGUOUS;
    }

    static String normalize(String raw) {
        String text = raw.trim().toLowerCase(Locale.ROOT);
        text = text.replaceAll("[\\p{Punct}\\p{IsPunctuation}]+", " ").replaceAll("\\s+", " ").trim();
        return text;
    }

    private static boolean containsToken(String haystack, String[] needles) {
        for (String needle : needles) {
            if (haystack.equals(needle) || haystack.startsWith(needle + " ")
                    || haystack.endsWith(" " + needle) || haystack.contains(" " + needle + " "))
                return true;
        }
        return false;
    }
}