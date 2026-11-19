package com.notcherry.lfm3pocket;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CactusWhistleBridgeTest {
    @Test public void extractsTextFromJsonResult() {
        assertEquals("hello world",
                CactusWhistleBridge.extractText("{\"text\":\" hello world \",\"language\":\"en\"}"));
    }

    @Test public void acceptsPlainTextResult() {
        assertEquals("hello world", CactusWhistleBridge.extractText("  hello world  "));
    }

    @Test public void missingTextBecomesEmpty() {
        assertEquals("", CactusWhistleBridge.extractText("{\"language\":\"en\"}"));
        assertEquals("", CactusWhistleBridge.extractText(null));
    }
}
