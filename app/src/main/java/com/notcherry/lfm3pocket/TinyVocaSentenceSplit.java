package com.notcherry.lfm3pocket;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Sentence/chunk rules aligned with tiny-voca {@code infer.sentences} / web worker.js. */
public final class TinyVocaSentenceSplit {
    private static final Pattern WS = Pattern.compile("\\s+");
    private static final Pattern SENTENCE = Pattern.compile(
            "(?<=[.!?…][\"')\\]]?)\\s+(?=[\"'(\\[]?[A-Z0-9])");
    private static final Pattern ABBR = Pattern.compile(
            "(?i)(?:\\b(?:mr|mrs|ms|dr|prof|sr|jr|st|vs|etc|no|inc|ltd|co|e\\.g|i\\.e|a\\.m|p\\.m)"
                    + "|\\b[A-Z])\\.$");
    private static final Pattern CLAUSE = Pattern.compile("(?<=[,;:—])\\s+");

    private TinyVocaSentenceSplit() {}

    public static String finished(String text) {
        String body = text.replaceAll("[\\s\\uFEFF]+$", "");
        if (body.isEmpty()) return text;
        String[] words = WS.split(body);
        String last = words[words.length - 1];
        boolean marked;
        if (last.chars().anyMatch(Character::isLetterOrDigit)) {
            marked = last.replaceAll("[\"'”’)\\]»]+$", "").matches(".*[.!?…,;:].*");
        } else {
            marked = last.indexOf('.') >= 0 || last.indexOf('!') >= 0 || last.indexOf('?') >= 0
                    || last.indexOf('…') >= 0 || ",;:".contains(last);
        }
        return marked ? text : body + ".";
    }

    public static List<String> sentences(String text) {
        String normalized = WS.matcher(text).replaceAll(" ").trim();
        if (normalized.isEmpty()) return List.of();
        String[] rough = SENTENCE.split(normalized);
        List<String> parts = new ArrayList<>();
        String current = "";
        for (String piece : rough) {
            current = current.isEmpty() ? piece : current + " " + piece;
            if (!ABBR.matcher(current).find()) {
                parts.add(current);
                current = "";
            }
        }
        if (!current.isEmpty()) parts.add(current);
        List<String> keep = new ArrayList<>();
        for (String part : parts) {
            if (part.chars().anyMatch(Character::isLetterOrDigit)) keep.add(part);
        }
        return keep;
    }

    public static List<String> shrinkForTokens(String sentence, int tokenBudget) {
        TinyVocaPhonemes phones = TinyVocaPhonemizerBridge.phonemize(sentence);
        if (phones == null || phones.ids.length <= tokenBudget) {
            return phones == null ? List.of() : List.of(sentence);
        }
        String[] clauses = CLAUSE.split(sentence);
        if (clauses.length > 1) {
            List<String> pieces = new ArrayList<>();
            for (String clause : clauses) pieces.addAll(shrinkForTokens(clause, tokenBudget));
            return pieces;
        }
        String[] words = sentence.split(" ");
        if (words.length < 2) return List.of();
        int half = (words.length + 1) / 2;
        String first = String.join(" ", java.util.Arrays.copyOfRange(words, 0, half));
        String second = String.join(" ", java.util.Arrays.copyOfRange(words, half, words.length));
        List<String> out = new ArrayList<>();
        out.addAll(shrinkForTokens(first, tokenBudget));
        out.addAll(shrinkForTokens(second, tokenBudget));
        return out;
    }
}