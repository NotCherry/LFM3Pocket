package com.notcherry.lfm3pocket;

import java.util.Collection;
import java.util.List;

/**
 * Generates compact {@code llama.cpp} GBNF grammars for Liquid AI LFM3 native XML tool calls:
 * <pre>
 * &lt;tool_call&gt;
 * &lt;function=TOOL_NAME&gt;
 * &lt;parameter=KEY&gt;VALUE&lt;/parameter&gt;
 * &lt;/function&gt;
 * &lt;/tool_call&gt;
 * </pre>
 *
 * <p>Intended for use with {@code llama_sampler_init_grammar_lazy_patterns} using
 * {@link #LAZY_TRIGGER_PATTERN}: normal prose runs unconstrained at full speed, and the moment
 * LFM3 emits {@code <tool_call>}, the grammar constrains function names and parameter keys.
 */
public final class LfmToolGrammar {
    /**
     * ECMAScript regex matched from the start of generation output; capture group 1 feeds
     * {@code <tool_call>} directly into the {@code root} grammar symbol.
     */
    public static final String LAZY_TRIGGER_PATTERN = "[\\s\\S]*?(<tool_call>)";
    public static final String ROOT_SYMBOL = "root";

    private LfmToolGrammar() {}

    /**
     * Builds a valid GBNF grammar constraining tool calls to the provided active tool definitions.
     * Returns an empty string if {@code tools} is null or empty.
     */
    public static String build(Collection<LfmToolCatalog.ToolSpec> tools) {
        if (tools == null || tools.isEmpty()) return "";
        StringBuilder out = new StringBuilder(1024);
        StringBuilder rootAlts = new StringBuilder();
        StringBuilder rules = new StringBuilder();
        int idx = 0;
        for (LfmToolCatalog.ToolSpec tool : tools) {
            if (tool == null || tool.name == null || tool.name.isEmpty()) continue;
            if (!isSafeIdentifier(tool.name)) continue;
            String ruleName = "tool-" + idx++;
            if (rootAlts.length() > 0) rootAlts.append(" | ");
            rootAlts.append(ruleName);

            rules.append(ruleName).append(" ::= \"<function=")
                    .append(escapeGbnfLiteral(tool.name)).append(">\" ws");
            List<String> keys = tool.parameterKeys;
            if (keys != null && !keys.isEmpty()) {
                rules.append(" (");
                int pCount = 0;
                for (String key : keys) {
                    if (key == null || !isSafeIdentifier(key)) continue;
                    if (pCount > 0) rules.append(" | ");
                    rules.append("\"<parameter=").append(escapeGbnfLiteral(key))
                            .append(">\" param-value \"</parameter>\" ws");
                    pCount++;
                }
                if (pCount > 0) {
                    rules.append(")*");
                } else {
                    // Remove trailing " ("
                    rules.setLength(rules.length() - 2);
                }
            }
            rules.append(" \"</function>\"\n");
        }
        if (idx == 0) return "";
        out.append("root ::= \"<tool_call>\" ws ( ").append(rootAlts).append(" ) ws \"</tool_call>\"\n");
        out.append(rules);
        out.append("param-value ::= ( [^<] | \"<\" [^/] | \"</\" [^p] )*\n");
        out.append("ws ::= [ \\t\\n\\r]*\n");
        return out.toString();
    }

    private static boolean isSafeIdentifier(String value) {
        if (value.isEmpty() || value.length() > 80) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            if (!ok) return false;
        }
        return true;
    }

    private static String escapeGbnfLiteral(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
