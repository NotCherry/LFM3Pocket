package com.notcherry.lfm3pocket;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * User-defined offline local Intent / URI shortcut (e.g. {@code obsidian://new?name={{title}}&content={{content}}},
 * {@code geo:0,0?q={{query}}}, Tasker/MacroDroid deep links).
 */
public final class CustomShortcut {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z_][a-zA-Z0-9_]{0,31})\\}\\}");
    private static final Pattern SCHEME = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]{1,31}):");
    private static final Set<String> BLOCKED_SCHEMES = new LinkedHashSet<>(
            java.util.Arrays.asList("javascript", "file", "content", "intent", "data", "blob"));

    public final String id;
    public final String label;
    public final String description;
    public final String uriTemplate;
    public final String packageName;
    public final List<String> placeholders;

    public CustomShortcut(String id, String label, String description, String uriTemplate, String packageName) {
        String cleanId = sanitizeId(id);
        if (cleanId.isEmpty()) throw new IllegalArgumentException("Shortcut id must contain letters or digits");
        String cleanUri = uriTemplate == null ? "" : uriTemplate.trim();
        validateUriTemplate(cleanUri);
        String cleanPkg = packageName == null ? "" : packageName.trim();
        if (!cleanPkg.isEmpty() && !cleanPkg.matches("(?:[A-Za-z][A-Za-z0-9_]*\\.)+[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid Android package name: " + cleanPkg);
        }
        this.id = cleanId;
        this.label = (label == null || label.trim().isEmpty()) ? cleanId : label.trim();
        this.description = (description == null || description.trim().isEmpty()) ? this.label : description.trim();
        this.uriTemplate = cleanUri;
        this.packageName = cleanPkg;
        this.placeholders = extractPlaceholders(cleanUri);
    }

    /** Wire tool name exposed to LFM3 (e.g. {@code shortcut.obsidian_note}). */
    public String wireName() {
        return "shortcut." + id;
    }

    /** Renders the URI template with URL-encoded parameter values. */
    public String renderUri(Map<String, Object> arguments) {
        String rendered = uriTemplate;
        for (String key : placeholders) {
            Object raw = arguments == null ? null : arguments.get(key);
            if (raw == null) {
                throw new IllegalArgumentException("Missing required shortcut parameter: " + key);
            }
            String value = String.valueOf(raw);
            for (int i = 0; i < value.length(); i++) {
                if (value.charAt(i) == '\0') throw new IllegalArgumentException("Invalid null char in " + key);
            }
            String encoded = urlEncode(value);
            rendered = rendered.replace("{{" + key + "}}", encoded);
        }
        validateUriTemplate(rendered);
        return rendered;
    }

    /** Builds a deferred {@link LfmToolCatalog.ToolSpec} for LFM3 {@code search_tools} discovery. */
    public LfmToolCatalog.ToolSpec toToolSpec() {
        StringBuilder props = new StringBuilder();
        StringBuilder req = new StringBuilder();
        for (int i = 0; i < placeholders.size(); i++) {
            String key = placeholders.get(i);
            if (i > 0) {
                props.append(',');
                req.append(',');
            }
            props.append('"').append(escapeJson(key)).append("\":{\"type\":\"string\"}");
            req.append('"').append(escapeJson(key)).append('"');
        }
        String schema = "{\"name\":\"" + escapeJson(wireName())
                + "\",\"defer_loading\":true,\"description\":\"" + escapeJson(description)
                + "\",\"parameters\":{\"type\":\"object\",\"properties\":{" + props
                + "},\"required\":[" + req + "]}}";
        String keywords = (id + " " + label + " " + description + " shortcut app deep link skrot " + packageName)
                .toLowerCase(Locale.ROOT);
        return new LfmToolCatalog.ToolSpec(wireName(), schema, placeholders, keywords, true, c -> true);
    }

    public static String sanitizeId(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length() && sb.length() < 40; i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            } else if (c == ' ' || c == '-' || c == '.') {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') sb.append('_');
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '_') sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    public static void validateUriTemplate(String uri) {
        if (uri == null || uri.isEmpty() || uri.length() > 1000) {
            throw new IllegalArgumentException("URI template must be 1..1000 characters");
        }
        for (int i = 0; i < uri.length(); i++) {
            char c = uri.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new IllegalArgumentException("URI template contains control characters");
            }
        }
        Matcher m = SCHEME.matcher(uri);
        if (!m.find()) {
            throw new IllegalArgumentException("URI template must start with a valid scheme like obsidian:// or geo:");
        }
        String scheme = m.group(1).toLowerCase(Locale.ROOT);
        if (BLOCKED_SCHEMES.contains(scheme)) {
            throw new IllegalArgumentException("URI scheme '" + scheme + "' is not allowed for shortcuts");
        }
    }

    public static List<String> extractPlaceholders(String template) {
        if (template == null || template.isEmpty()) return Collections.emptyList();
        LinkedHashSet<String> found = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find() && found.size() < 12) {
            found.add(m.group(1));
        }
        return Collections.unmodifiableList(new ArrayList<>(found));
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (Exception e) {
            return value;
        }
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }
}
