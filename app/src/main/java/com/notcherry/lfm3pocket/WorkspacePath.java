package com.notcherry.lfm3pocket;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Pure-Java path validation and Markdown note helpers for the offline SAF workspace vault.
 * Enforces a strict relative-path jail: no absolute paths, no {@code ..} traversal, no backslashes,
 * and no control characters.
 */
public final class WorkspacePath {
    static final int MAX_DEPTH = 8;
    static final int MAX_SEGMENT_LENGTH = 120;
    static final int MAX_PATH_LENGTH = 512;
    static final String TRASH_DIR = ".hanami-trash";

    private WorkspacePath() {}

    /**
     * Validates and splits a relative workspace path into clean path segments.
     * Empty or {@code "/"} / {@code "."} returns an empty list (workspace root) when {@code allowRoot} is true.
     *
     * @throws IllegalArgumentException if the path escapes the workspace jail or contains unsafe chars
     */
    public static List<String> parseSegments(String rawPath, boolean allowRoot) {
        if (rawPath == null) {
            if (allowRoot) return Collections.emptyList();
            throw new IllegalArgumentException("path is required");
        }
        String trimmed = rawPath.trim();
        if (trimmed.isEmpty() || ".".equals(trimmed) || "/".equals(trimmed)) {
            if (allowRoot) return Collections.emptyList();
            throw new IllegalArgumentException("path must point to a file or folder inside the workspace");
        }
        if (trimmed.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException("path is too long");
        }
        if (trimmed.startsWith("/") || trimmed.startsWith("\\") || trimmed.contains("\\")) {
            throw new IllegalArgumentException("path must be relative and use '/' separators");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == 0 || c < 0x20 || c == 0x7f) {
                throw new IllegalArgumentException("path contains invalid control characters");
            }
        }
        String[] parts = trimmed.split("/", -1);
        List<String> segments = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("path cannot contain '.' or '..' traversal segments");
            }
            if (part.contains(":")) {
                throw new IllegalArgumentException("path segment cannot contain ':'");
            }
            if (part.length() > MAX_SEGMENT_LENGTH) {
                throw new IllegalArgumentException("path segment is too long");
            }
            segments.add(part);
        }
        if (segments.isEmpty()) {
            if (allowRoot) return Collections.emptyList();
            throw new IllegalArgumentException("path must not be empty");
        }
        if (segments.size() > MAX_DEPTH) {
            throw new IllegalArgumentException("path nesting exceeds max depth (" + MAX_DEPTH + ")");
        }
        return Collections.unmodifiableList(segments);
    }

    /** Normalizes a relative path into canonical {@code a/b/c} form (or {@code ""} for root). */
    public static String normalize(String rawPath, boolean allowRoot) {
        List<String> segments = parseSegments(rawPath, allowRoot);
        if (segments.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) out.append('/');
            out.append(segments.get(i));
        }
        return out.toString();
    }

    /** Converts a human note title into a safe {@code .md} filename. */
    public static String noteFilename(String title) {
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("title is required");
        }
        StringBuilder sb = new StringBuilder();
        boolean lastDash = false;
        String trimmed = title.trim();
        for (int i = 0; i < trimmed.length() && sb.length() < 80; i++) {
            char c = trimmed.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
                lastDash = false;
            } else if (c == ' ' || c == '-' || c == '_' || c == '.') {
                if (sb.length() > 0 && !lastDash) {
                    sb.append('-');
                    lastDash = true;
                }
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
            sb.setLength(sb.length() - 1);
        }
        String base = sb.length() == 0 ? "note" : sb.toString();
        if (base.toLowerCase(Locale.ROOT).endsWith(".md")) {
            return base;
        }
        return base + ".md";
    }

    /** Builds a clean Markdown note with optional YAML frontmatter when tags or title are provided. */
    public static String formatMarkdownNote(String title, String content, List<String> tags, ZonedDateTime now) {
        String cleanTitle = title == null ? "" : title.replace('\n', ' ').replace('\r', ' ').trim();
        String body = content == null ? "" : content;
        ZonedDateTime ts = now == null ? ZonedDateTime.now() : now;
        StringBuilder out = new StringBuilder();
        out.append("---\n");
        out.append("title: \"").append(escapeYaml(cleanTitle)).append("\"\n");
        out.append("created: ").append(ts.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).append('\n');
        if (tags != null && !tags.isEmpty()) {
            out.append("tags: [");
            boolean first = true;
            for (String tag : tags) {
                if (tag == null) continue;
                String cleanTag = tag.replace('\n', ' ').replace('\r', ' ').replace(",", "").trim();
                if (cleanTag.isEmpty()) continue;
                if (!first) out.append(", ");
                out.append('"').append(escapeYaml(cleanTag)).append('"');
                first = false;
            }
            out.append("]\n");
        }
        out.append("---\n\n");
        if (!body.startsWith("# ") && !cleanTitle.isEmpty()) {
            out.append("# ").append(cleanTitle).append("\n\n");
        }
        out.append(body);
        if (!body.endsWith("\n")) out.append('\n');
        return out.toString();
    }

    public static List<String> parseTags(String rawTags) {
        if (rawTags == null || rawTags.trim().isEmpty()) return Collections.emptyList();
        String[] parts = rawTags.split("[,;\\n]+");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.startsWith("#")) trimmed = trimmed.substring(1).trim();
            if (!trimmed.isEmpty() && out.size() < 16) out.add(trimmed);
        }
        return Collections.unmodifiableList(out);
    }

    public static boolean isReadableTextExtension(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt")
                || lower.endsWith(".json") || lower.endsWith(".yaml") || lower.endsWith(".yml")
                || lower.endsWith(".csv") || lower.endsWith(".xml") || lower.endsWith(".html")
                || lower.endsWith(".htm") || lower.endsWith(".log") || lower.endsWith(".ini")
                || lower.endsWith(".cfg") || lower.endsWith(".conf") || lower.endsWith(".toml")
                || lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".py")
                || lower.endsWith(".js") || lower.endsWith(".ts") || lower.endsWith(".tsx")
                || lower.endsWith(".c") || lower.endsWith(".cpp") || lower.endsWith(".h")
                || lower.endsWith(".rs") || lower.endsWith(".go") || lower.endsWith(".sh")
                || lower.endsWith(".sql") || lower.endsWith(".org") || lower.endsWith(".rst");
    }

    private static String escapeYaml(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
