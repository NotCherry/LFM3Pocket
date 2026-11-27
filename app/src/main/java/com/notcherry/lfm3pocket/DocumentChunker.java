package com.notcherry.lfm3pocket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Splits extracted document text into bounded prompt chunks. */
final class DocumentChunker {
    static final int DEFAULT_MAX_TOTAL_CHARS = 12000;

    private DocumentChunker() {}

    static List<String> chunk(String text, int maxChunkChars, int maxTotalChars) {
        if (text == null) return Collections.emptyList();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isEmpty()) return Collections.emptyList();
        int chunkLimit = Math.max(50, maxChunkChars);
        int totalLimit = Math.max(chunkLimit, maxTotalChars);
        if (normalized.length() > totalLimit) {
            normalized = normalized.substring(0, totalLimit).trim();
        }
        if (normalized.isEmpty()) return Collections.emptyList();

        ArrayList<String> chunks = new ArrayList<>();
        int index = 0;
        while (index < normalized.length()) {
            int end = Math.min(normalized.length(), index + chunkLimit);
            if (end < normalized.length()) {
                int split = lastBreak(normalized, index, end);
                if (split > index + (chunkLimit / 2)) end = split;
            }
            String chunk = normalized.substring(index, end).trim();
            if (!chunk.isEmpty()) chunks.add(chunk);
            index = end;
            while (index < normalized.length() && Character.isWhitespace(normalized.charAt(index))) index++;
        }
        return chunks;
    }

    private static int lastBreak(String text, int start, int end) {
        int paragraph = text.lastIndexOf("\n\n", end);
        if (paragraph >= start) return paragraph;
        int line = text.lastIndexOf('\n', end);
        if (line >= start) return line;
        int space = text.lastIndexOf(' ', end);
        if (space >= start) return space;
        return end;
    }
}
