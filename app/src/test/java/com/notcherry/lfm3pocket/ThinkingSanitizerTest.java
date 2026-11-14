package com.notcherry.lfm3pocket;

import org.junit.Test;
import static org.junit.Assert.*;

public class ThinkingSanitizerTest {
    @Test public void stripsClosedThinkAndExtractsReasoning() {
        ThinkingSanitizer.Cleaned cleaned = ThinkingSanitizer.clean(
                "<think>plan the answer</think>\nHello **world**");
        assertEquals("Hello **world**", cleaned.answer);
        assertEquals("plan the answer", cleaned.reasoning);
        assertFalse(ThinkingSanitizer.containsRawMarkers(cleaned.answer));
    }

    @Test public void stripsUnclosedThinkDuringStream() {
        ThinkingSanitizer.Cleaned cleaned = ThinkingSanitizer.clean(
                "Visible lead <think>still thinking about cats");
        assertEquals("Visible lead", cleaned.answer);
        assertEquals("still thinking about cats", cleaned.reasoning);
        assertFalse(cleaned.answer.contains("<think>"));
    }

    @Test public void stripsOmniTemplateJunkFromAnswer() {
        ThinkingSanitizer.Cleaned cleaned = ThinkingSanitizer.clean(
                "<|mixed_start|>hello<|mixed_end|> <|im_start|>assistant reply <|im_end|>"
                        + "<|audio_start|>x<|audio_end|><|startoftext|><__media__> done");
        assertFalse(ThinkingSanitizer.containsRawMarkers(cleaned.answer));
        assertTrue(cleaned.answer.contains("hello"));
        assertTrue(cleaned.answer.contains("reply"));
        assertTrue(cleaned.answer.contains("done"));
        assertNull(cleaned.reasoning);
    }

    @Test public void multipleThinkBlocksConcatenateReasoning() {
        ThinkingSanitizer.Cleaned cleaned = ThinkingSanitizer.clean(
                "<think>first</think>Mid<think>second</think>End");
        assertEquals("MidEnd", cleaned.answer);
        assertFalse(ThinkingSanitizer.containsRawMarkers(cleaned.answer));
        assertEquals("first\nsecond", cleaned.reasoning);
    }

    @Test public void emptyThinkLeavesNullReasoning() {
        ThinkingSanitizer.Cleaned cleaned = ThinkingSanitizer.clean("<think></think>Only answer");
        assertEquals("Only answer", cleaned.answer);
        assertNull(cleaned.reasoning);
    }

    @Test public void nullAndEmptyAreSafe() {
        assertEquals("", ThinkingSanitizer.clean(null).answer);
        assertNull(ThinkingSanitizer.clean("").reasoning);
    }
}
