package com.notcherry.lfm3pocket;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TinyVocaSentenceSplitTest {
    @Test
    public void addsTerminalPeriodWhenMissing() {
        assertEquals("Hello there.", TinyVocaSentenceSplit.finished("Hello there"));
    }

    @Test
    public void splitsSentencesLikeReference() {
        List<String> parts = TinyVocaSentenceSplit.sentences("Hello there.  How are you?");
        assertEquals(2, parts.size());
        assertEquals("Hello there.", parts.get(0));
        assertEquals("How are you?", parts.get(1));
    }

    @Test
    public void keepsAbbreviationBoundaries() {
        List<String> parts = TinyVocaSentenceSplit.sentences("Dr. Smith left. Then we spoke.");
        assertTrue(parts.get(0).contains("Dr."));
    }
}