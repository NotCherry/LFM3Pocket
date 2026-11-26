package com.notcherry.lfm3pocket;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an assistant reply into an allowlisted tool proposal.
 *
 * <p>Supports:
 * <ol>
 *   <li>Liquid AI LFM3 native XML tool calls ({@code <tool_call><function=...><parameter=...>...</parameter></function></tool_call>})</li>
 *   <li>Liquid AI LFM2 / LFM2.5 Pythonic tool calls ({@code <|tool_call_start|>[func(arg="val")]<|tool_call_end|>})</li>
 *   <li>Whole-message or single fenced {@code json} tool objects ({@code {"tool":"...","arguments":{...}}})</li>
 * </ol>
 *
 * <p>Prose around a bare JSON fragment is still ignored so the model cannot smuggle an action
 * inside a paragraph. Authorization always goes through {@link ToolCoordinator} as
 * {@code MODEL_PROPOSAL}.
 */
public final class ModelToolBridge {
    private static final Pattern XML_TOOL_CALL =
            Pattern.compile("(?s)<tool_call>\\s*(.*?)\\s*</tool_call>");
    private static final Pattern XML_FUNCTION =
            Pattern.compile("(?s)<function=([^>\\s]+)>\\s*(.*?)\\s*</function>");
    private static final Pattern XML_PARAMETER =
            Pattern.compile("(?s)<parameter=([^>\\s]+)>\\s*(.*?)\\s*</parameter>");
    private static final Pattern LFM2_TOOL_CALL =
            Pattern.compile("(?s)<\\|tool_call_start\\|>\\s*(.*?)\\s*<\\|tool_call_end\\|>");
    private static final Pattern PYTHONIC_CALL =
            Pattern.compile("(?s)^\\[?\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*)\\)\\s*\\]?$");

    public static final class ExtractedCall {
        public final String toolName;
        public final Map<String, Object> arguments;
        public final String rawBlock;

        ExtractedCall(String toolName, Map<String, Object> arguments, String rawBlock) {
            this.toolName = toolName;
            this.arguments = arguments;
            this.rawBlock = rawBlock;
        }
    }

    private ModelToolBridge() {}

    public static String extractToolJson(String assistantText) {
        if (assistantText == null) return null;
        String trimmed = assistantText.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed;
        int fence = trimmed.indexOf("```");
        if (fence < 0) return null;
        int langEnd = trimmed.indexOf('\n', fence + 3);
        if (langEnd < 0) return null;
        String lang = trimmed.substring(fence + 3, langEnd).trim().toLowerCase(Locale.ROOT);
        if (!lang.isEmpty() && !lang.equals("json")) return null;
        int close = trimmed.indexOf("```", langEnd + 1);
        if (close < 0) return null;
        String block = trimmed.substring(langEnd + 1, close).trim();
        if (block.startsWith("{") && block.endsWith("}")) return block;
        return null;
    }

    /** Extracts a structured LFM3 XML or LFM2 Pythonic tool call when present, otherwise null. */
    public static ExtractedCall extractStructuredCall(String assistantText) {
        if (assistantText == null || assistantText.isEmpty()) return null;

        Matcher xmlBlock = XML_TOOL_CALL.matcher(assistantText);
        if (xmlBlock.find()) {
            String inner = xmlBlock.group(1);
            ExtractedCall fromXml = parseXmlFunction(inner, xmlBlock.group(0));
            if (fromXml != null) return fromXml;
            // Some templates wrap a JSON object inside <tool_call>{...}</tool_call>.
            String trimmedInner = inner.trim();
            if (trimmedInner.startsWith("{") && trimmedInner.endsWith("}")) {
                return parseJsonNameArgs(trimmedInner, xmlBlock.group(0));
            }
        }

        Matcher bareFn = XML_FUNCTION.matcher(assistantText);
        if (bareFn.find()) {
            return parseXmlFunction(bareFn.group(0), bareFn.group(0));
        }

        Matcher lfm2 = LFM2_TOOL_CALL.matcher(assistantText);
        if (lfm2.find()) {
            String body = lfm2.group(1).trim();
            if (body.startsWith("{") && body.endsWith("}")) {
                return parseJsonNameArgs(body, lfm2.group(0));
            }
            return parsePythonicCall(body, lfm2.group(0));
        }
        return null;
    }

    /** True when the reply contains any explicit tool-call syntax (valid or malformed). */
    public static boolean containsToolMarkup(String assistantText) {
        if (assistantText == null || assistantText.isEmpty()) return false;
        return assistantText.contains("<tool_call>")
                || assistantText.contains("<function=")
                || assistantText.contains("<|tool_call_start|>");
    }

    /**
     * Strips raw LFM3 XML / LFM2 Pythonic tool-call markup (including unclosed blocks during
     * streaming) so users never see raw protocol tags in chat bubbles.
     */
    public static String stripToolCallMarkup(String text) {
        if (text == null || text.isEmpty()) return "";
        String work = XML_TOOL_CALL.matcher(text).replaceAll("");
        work = XML_FUNCTION.matcher(work).replaceAll("");
        work = LFM2_TOOL_CALL.matcher(work).replaceAll("");
        int openXml = work.indexOf("<tool_call>");
        if (openXml >= 0) work = work.substring(0, openXml);
        int openFn = work.indexOf("<function=");
        if (openFn >= 0) work = work.substring(0, openFn);
        int openLfm2 = work.indexOf("<|tool_call_start|>");
        if (openLfm2 >= 0) work = work.substring(0, openLfm2);
        return work.replace("</tool_call>", "")
                .replace("</function>", "")
                .replace("<|tool_call_end|>", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    /**
     * Parses a tool call from assistant text using LFM3 XML, LFM2 Pythonic, or legacy JSON.
     *
     * @return null if the text contains no tool call; otherwise a {@link ToolRegistry.Parsed}
     *     (which may be invalid so an agent loop can feed the validation error back to the model).
     */
    public static ToolRegistry.Parsed parseFromAssistant(ToolRegistry registry, String assistantText) {
        if (registry == null || assistantText == null || assistantText.trim().isEmpty()) return null;
        ExtractedCall structured = extractStructuredCall(assistantText);
        if (structured != null) {
            return registry.parseCall(structured.toolName, structured.arguments);
        }
        String json = extractToolJson(assistantText);
        if (json == null) return null;
        return registry.parse(json);
    }

    /**
     * @return a coordinator outcome when the reply was a valid tool call, otherwise null
     *     when there was nothing to propose (not an error).
     */
    public static ToolCoordinator.Outcome proposeFromAssistant(
            ToolRegistry registry, ToolCoordinator coordinator, String assistantText) {
        ToolRegistry.Parsed parsed = parseFromAssistant(registry, assistantText);
        // Invalid or unknown tools stay inert. They never reach a handler.
        if (parsed == null || !parsed.isValid()) return null;
        return coordinator.propose(parsed.request, ToolCoordinator.Source.MODEL_PROPOSAL);
    }

    private static ExtractedCall parseXmlFunction(String text, String rawBlock) {
        Matcher fn = XML_FUNCTION.matcher(text);
        if (!fn.find()) return null;
        String toolName = fn.group(1).trim();
        String paramsBody = fn.group(2);
        Map<String, Object> args = new LinkedHashMap<>();
        Matcher param = XML_PARAMETER.matcher(paramsBody);
        while (param.find()) {
            String key = param.group(1).trim();
            String rawVal = param.group(2).trim();
            args.put(key, coerceTypedParameter(toolName, key, rawVal));
        }
        return new ExtractedCall(toolName, args, rawBlock);
    }

    @SuppressWarnings("unchecked")
    private static ExtractedCall parseJsonNameArgs(String json, String rawBlock) {
        try {
            Object parsed = StrictToolJson.parse(json);
            if (!(parsed instanceof Map)) return null;
            Map<String, Object> obj = (Map<String, Object>) parsed;
            Object tool = obj.containsKey("tool") ? obj.get("tool") : obj.get("name");
            Object args = obj.get("arguments");
            if (!(tool instanceof String)) return null;
            Map<String, Object> map = args instanceof Map
                    ? new LinkedHashMap<>((Map<String, Object>) args)
                    : new LinkedHashMap<>();
            return new ExtractedCall((String) tool, map, rawBlock);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ExtractedCall parsePythonicCall(String body, String rawBlock) {
        Matcher m = PYTHONIC_CALL.matcher(body.trim());
        if (!m.matches()) return null;
        String toolName = m.group(1).trim();
        String argString = m.group(2).trim();
        Map<String, Object> args = new LinkedHashMap<>();
        if (!argString.isEmpty()) {
            List<String> pairs = splitTopLevelCommas(argString);
            for (String pair : pairs) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String key = pair.substring(0, eq).trim();
                String rawVal = unquotePythonValue(pair.substring(eq + 1).trim());
                args.put(key, coerceTypedParameter(toolName, key, rawVal));
            }
        }
        return new ExtractedCall(toolName, args, rawBlock);
    }

    private static List<String> splitTopLevelCommas(String input) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        int depth = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == '\\' && i + 1 < input.length()) {
                    cur.append(input.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                cur.append(c);
            } else if (c == '[' || c == '{' || c == '(') {
                depth++;
                cur.append(c);
            } else if (c == ']' || c == '}' || c == ')') {
                if (depth > 0) depth--;
                cur.append(c);
            } else if (c == ',' && depth == 0) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString().trim());
        return out;
    }

    private static String unquotePythonValue(String val) {
        if (val.length() >= 2) {
            char first = val.charAt(0);
            char last = val.charAt(val.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                String inner = val.substring(1, val.length() - 1);
                return inner.replace("\\n", "\n").replace("\\t", "\t")
                        .replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\");
            }
        }
        return val;
    }

    /**
     * Converts XML/Pythonic string parameter values into the strict Java types expected by
     * {@link ToolRegistry#parseCall(String, Map)} based on parameter name.
     */
    private static Object coerceTypedParameter(String toolName, String key, String raw) {
        if (raw == null) return "";
        if ("on".equals(key) || "all_day".equals(key)) {
            if ("true".equalsIgnoreCase(raw)) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(raw)) return Boolean.FALSE;
            return raw;
        }
        if ("seconds".equals(key) || "hour".equals(key) || "minute".equals(key)
                || "percent".equals(key) || "limit".equals(key) || "duration_minutes".equals(key)
                || "max_depth".equals(key) || "start_line".equals(key) || "max_lines".equals(key)
                || key.endsWith("_epoch_sec")) {
            try {
                return Long.parseLong(raw.trim());
            } catch (NumberFormatException ignored) {
                return raw;
            }
        }
        if ("value".equals(key) || "latitude".equals(key) || "longitude".equals(key)) {
            try {
                if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                    return Double.parseDouble(raw.trim());
                }
                return Long.parseLong(raw.trim());
            } catch (NumberFormatException ignored) {
                return raw;
            }
        }
        if ("days".equals(key) && raw.startsWith("[") && raw.endsWith("]")) {
            try {
                Object parsed = StrictToolJson.parse(raw);
                if (parsed instanceof List) return parsed;
            } catch (IllegalArgumentException ignored) {
                // Fall through; validator will reject cleanly.
            }
        }
        return raw;
    }
}
