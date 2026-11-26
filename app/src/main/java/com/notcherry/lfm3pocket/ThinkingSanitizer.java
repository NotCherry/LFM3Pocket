package com.notcherry.lfm3pocket;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips thinking / Omni template junk from assistant text and extracts optional reasoning (U06).
 * Apply at session→UI boundary (stream emit + finish). Answer body must never retain tag markers.
 */
public final class ThinkingSanitizer {
    private static final Pattern CLOSED_THINK =
            Pattern.compile("(?s)<think>(.*?)</think>");
    private static final Pattern IM_START =
            Pattern.compile("<\\|im_start\\|>\\w*");
    private static final String THINK_OPEN = "<think>";
    private static final String THINK_CLOSE = "</think>";

    public static final class Cleaned {
        public final String answer;
        /** Null when empty after trim. */
        public final String reasoning;

        public Cleaned(String answer, String reasoning) {
            this.answer = answer == null ? "" : answer;
            this.reasoning = reasoning == null || reasoning.isEmpty() ? null : reasoning;
        }
    }

    private ThinkingSanitizer() {}

    public static Cleaned clean(String raw) {
        return clean(raw, false);
    }

    /**
     * Cleans raw assistant output. When {@code prefilledThink} is true, the prompt itself opened
     * {@code <think>} before generation began, so tokens up to the first {@code </think>} (or all
     * streamed tokens until {@code </think>} arrives) are extracted as reasoning.
     */
    public static Cleaned clean(String raw, boolean prefilledThink) {
        if (raw == null || raw.isEmpty()) return new Cleaned("", null);
        StringBuilder reasoning = new StringBuilder();
        String work = raw;
        if (prefilledThink && !startsWithThinkOpen(work)) {
            work = THINK_OPEN + work;
        }

        Matcher closed = CLOSED_THINK.matcher(work);
        StringBuffer withoutClosed = new StringBuffer();
        while (closed.find()) {
            appendReasoning(reasoning, closed.group(1));
            closed.appendReplacement(withoutClosed, "");
        }
        closed.appendTail(withoutClosed);
        work = withoutClosed.toString();

        int open = work.indexOf(THINK_OPEN);
        if (open >= 0) {
            appendReasoning(reasoning, work.substring(open + THINK_OPEN.length()));
            work = work.substring(0, open);
        }
        work = work.replace(THINK_CLOSE, "");
        work = stripTemplateJunk(work);
        work = collapseBlankLines(work).trim();

        String reason = reasoning.toString().trim();
        return new Cleaned(work, reason.isEmpty() ? null : reason);
    }

    private static boolean startsWithThinkOpen(String value) {
        int i = 0;
        while (i < value.length() && Character.isWhitespace(value.charAt(i))) i++;
        return value.startsWith(THINK_OPEN, i);
    }

    /** True if the visible answer still contains thinking or known special-token markers. */
    public static boolean containsRawMarkers(String answer) {
        if (answer == null || answer.isEmpty()) return false;
        return answer.contains(THINK_OPEN)
                || answer.contains(THINK_CLOSE)
                || answer.contains("<|")
                || answer.contains("<__media__>");
    }

    private static void appendReasoning(StringBuilder reasoning, String chunk) {
        if (chunk == null) return;
        String trimmed = chunk.trim();
        if (trimmed.isEmpty()) return;
        if (reasoning.length() > 0) reasoning.append('\n');
        reasoning.append(trimmed);
    }

    private static String stripTemplateJunk(String value) {
        String work = value;
        work = work.replace("<|mixed_start|>", "")
                .replace("<|mixed_end|>", "")
                .replace("<|audio_start|>", "")
                .replace("<|audio_end|>", "")
                .replace("<|startoftext|>", "")
                .replace("<|im_end|>", "")
                .replace("<__media__>", "");
        work = IM_START.matcher(work).replaceAll("");
        return work;
    }

    private static String collapseBlankLines(String value) {
        return value.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n");
    }
}
