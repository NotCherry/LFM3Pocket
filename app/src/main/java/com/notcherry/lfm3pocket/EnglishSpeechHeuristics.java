package com.notcherry.lfm3pocket;

import java.util.Locale;
import java.util.regex.Pattern;

/** Heuristic gate for optional English-only on-device tiny-voca TTS. */
public final class EnglishSpeechHeuristics {
    private static final Pattern POLISH =
            Pattern.compile("[ąćęłńóśźżĄĆĘŁŃÓŚŹŻ]");
    private static final Pattern LATIN_LETTER =
            Pattern.compile("[A-Za-z]");
    private static final Pattern ASCII_DIGIT =
            Pattern.compile("[0-9]");
    private static final Pattern NON_ASCII_LETTER =
            Pattern.compile("[\\p{L}&&[^A-Za-z]]");

    private EnglishSpeechHeuristics() {}

    public enum LikelyLanguage { ENGLISH, NON_ENGLISH, EMPTY }

  /** Strip markdown-ish noise already removed upstream; detect obvious Polish vs Latin letters. */
    public static LikelyLanguage classify(String text) {
        if (text == null || text.isBlank()) return LikelyLanguage.EMPTY;
        if (POLISH.matcher(text).find()) return LikelyLanguage.NON_ENGLISH;
        if (LATIN_LETTER.matcher(text).find()) return LikelyLanguage.ENGLISH;
        if (ASCII_DIGIT.matcher(text).find() && !NON_ASCII_LETTER.matcher(text).find()) {
            return LikelyLanguage.ENGLISH;
        }
        return LikelyLanguage.NON_ENGLISH;
    }

    public static String normalizeForTts(String reply) {
        return SpeechPrompt.spokenText(reply == null ? "" : reply);
    }
}