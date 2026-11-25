package com.notcherry.lfm3pocket;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Unit tests for LfmToolGrammar GBNF output and lazy trigger pattern. */
public final class LfmToolGrammarTest {
    @Test public void buildsValidGbnfForActiveToolsAndTriggerMatchesProsePrefix() {
        LfmToolCatalog.Capabilities caps = new LfmToolCatalog.Capabilities(
                true, false, true, true, "Personal", false, true, "Vault");
        List<LfmToolCatalog.ToolSpec> core = LfmToolCatalog.coreTools(caps);
        String gbnf = LfmToolGrammar.build(core);

        assertTrue(gbnf.startsWith("root ::= \"<tool_call>\" ws ( "));
        assertTrue(gbnf.contains("<function=search_tools>"));
        assertTrue(gbnf.contains("<function=web_search>"));
        assertTrue(gbnf.contains("<function=create_event>"));
        assertTrue(gbnf.contains("<function=note_create>"));
        assertTrue(gbnf.contains("<parameter=title>"));
        assertTrue(gbnf.contains("</function>"));
        assertTrue(gbnf.contains("param-value ::="));
        assertFalse("Deferred tools should not be in core grammar before search_tools",
                gbnf.contains("<function=toggle_flashlight>"));

        Pattern p = Pattern.compile(LfmToolGrammar.LAZY_TRIGGER_PATTERN);
        Matcher m = p.matcher("Sure, let me check.\n<tool_call>\n<function=web_search>");
        assertTrue("Trigger must match across newlines after prose", m.find());
        assertEquals("<tool_call>", m.group(1));
    }

    @Test public void returnsEmptyGrammarWhenNoToolsProvided() {
        assertEquals("", LfmToolGrammar.build(Collections.emptyList()));
        assertEquals("", LfmToolGrammar.build(null));
    }

    @Test public void lazyTriggerPatternAvoidsCatastrophicLeadingWildcard() {
        assertFalse("Leading [\\s\\S]*? causes O(N^2)-per-token std::regex_search backtracking in llama.cpp",
                LfmToolGrammar.LAZY_TRIGGER_PATTERN.contains("[\\s\\S]*"));
        assertFalse("Leading .* causes quadratic std::regex_search backtracking",
                LfmToolGrammar.LAZY_TRIGGER_PATTERN.startsWith(".*"));

        StringBuilder longProse = new StringBuilder();
        for (int i = 0; i < 2000; i++) longProse.append("word ");
        longProse.append("\n<tool_call>\n<function=current_time></function></tool_call>");
        Pattern p = Pattern.compile(LfmToolGrammar.LAZY_TRIGGER_PATTERN);
        Matcher m = p.matcher(longProse);
        assertTrue(m.find());
        assertEquals("<tool_call>", m.group(1));
    }
}
