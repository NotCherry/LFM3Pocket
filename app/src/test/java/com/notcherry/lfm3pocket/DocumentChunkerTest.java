package com.notcherry.lfm3pocket;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class DocumentChunkerTest {
    @Test public void emptyOrWhitespaceProducesNoChunks() {
        assertTrue(DocumentChunker.chunk(null, 100, 1000).isEmpty());
        assertTrue(DocumentChunker.chunk("", 100, 1000).isEmpty());
        assertTrue(DocumentChunker.chunk(" \n\t ", 100, 1000).isEmpty());
    }

    @Test public void shortTextBecomesSingleChunk() {
        List<String> chunks = DocumentChunker.chunk("Hello document", 100, 1000);
        assertEquals(1, chunks.size());
        assertEquals("Hello document", chunks.get(0));
    }

    @Test public void longTextIsSplitWithinChunkLimit() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 50; i++) text.append("Paragraph ").append(i).append(".\n\n");
        List<String> chunks = DocumentChunker.chunk(text.toString(), 120, 5000);
        assertTrue(chunks.size() > 1);
        for (String chunk : chunks) assertTrue("chunk too large: " + chunk.length(), chunk.length() <= 120);
        String joined = String.join("\n", chunks);
        assertTrue(joined.contains("Paragraph 0."));
        assertTrue(joined.contains("Paragraph 49."));
    }

    @Test public void totalLimitWindowsContentUnderRamBudget() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 2000; i++) text.append('a');
        List<String> chunks = DocumentChunker.chunk(text.toString(), 300, 500);
        int total = 0;
        for (String chunk : chunks) total += chunk.length();
        assertTrue("total exceeded budget: " + total, total <= 500);
        assertFalse(chunks.isEmpty());
    }
}
