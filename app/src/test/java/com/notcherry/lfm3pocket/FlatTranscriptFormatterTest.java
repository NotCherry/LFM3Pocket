package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.notcherry.lfm3pocket.FlatTranscriptFormatter.Role;
import com.notcherry.lfm3pocket.FlatTranscriptFormatter.Source;
import com.notcherry.lfm3pocket.FlatTranscriptFormatter.Turn;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/** JVM contract: structured turns still produce YOU / LFM3 / ▌ flat transcript text. */
public final class FlatTranscriptFormatterTest {

    @Test
    public void emptyTurnsMatchHistoricalPlaceholder() {
        assertEquals("No conversation yet.\n", FlatTranscriptFormatter.format(Collections.emptyList()));
    }

    @Test
    public void multiTurnUserAndAssistantParity() {
        List<Turn> turns = Arrays.asList(
                new Turn(Role.USER, "Hello", false, Collections.emptyList()),
                new Turn(Role.ASSISTANT, "Hi there", false, Collections.emptyList()),
                new Turn(Role.USER, "Second", false, Collections.emptyList()),
                new Turn(Role.ASSISTANT, "Reply two", false, Collections.emptyList())
        );
        String flat = FlatTranscriptFormatter.format(turns);
        assertEquals(
                "YOU\nHello\n\nLFM3\nHi there\n\nYOU\nSecond\n\nLFM3\nReply two\n\n",
                flat);
        assertTrue(flat.contains("YOU\nHello"));
        assertTrue(flat.contains("LFM3\nHi there"));
    }

    @Test
    public void streamingAssistantKeepsCursorWithoutTrailingBlank() {
        List<Turn> turns = Arrays.asList(
                new Turn(Role.USER, "Stream please", false, Collections.emptyList()),
                new Turn(Role.ASSISTANT, "Partial", true, Collections.emptyList())
        );
        String flat = FlatTranscriptFormatter.format(turns);
        assertEquals("YOU\nStream please\n\nLFM3\nPartial▌", flat);
        assertTrue(flat.endsWith("Partial" + FlatTranscriptFormatter.STREAM_CURSOR));
        assertFalse(flat.endsWith("▌\n\n"));
    }

    @Test
    public void cancelledTurnIsFinishedAssistantWithoutCursor() {
        // ChatSession finish() commits partial text as a completed message after cancel.
        List<Turn> turns = Arrays.asList(
                new Turn(Role.USER, "Abort me", false, Collections.emptyList()),
                new Turn(Role.ASSISTANT, "Stopped mid", false, Collections.emptyList())
        );
        String flat = FlatTranscriptFormatter.format(turns);
        assertEquals("YOU\nAbort me\n\nLFM3\nStopped mid\n\n", flat);
        assertFalse(flat.contains(String.valueOf(FlatTranscriptFormatter.STREAM_CURSOR)));
    }

    @Test
    public void completedAssistantAppendsNumberedSources() {
        List<Source> sources = Arrays.asList(
                new Source("Alpha", "https://example.com/a"),
                new Source("Beta", "https://example.com/b")
        );
        String flat = FlatTranscriptFormatter.format(
                new Turn(Role.USER, "Search?", false, Collections.emptyList()),
                new Turn(Role.ASSISTANT, "Answer [1]", false, sources)
        );
        assertEquals(
                "YOU\nSearch?\n\nLFM3\nAnswer [1]\n\n[1] Alpha\nhttps://example.com/a\n[2] Beta\nhttps://example.com/b\n",
                flat);
    }

    @Test
    public void streamingIgnoresSourcesInFlatString() {
        List<Source> sources = Collections.singletonList(
                new Source("ShouldNotAppear", "https://example.com/x"));
        String flat = FlatTranscriptFormatter.format(
                new Turn(Role.ASSISTANT, "Still going", true, sources)
        );
        assertEquals("LFM3\nStill going▌", flat);
        assertFalse(flat.contains("ShouldNotAppear"));
    }

    @Test
    public void cleanedAnswerPassesThroughWithoutTagSpillExpectation() {
        // Formatter does not sanitize; U06 owns stripping. Caller supplies cleaned body.
        String cleaned = "Final answer without think markers";
        String flat = FlatTranscriptFormatter.format(
                new Turn(Role.ASSISTANT, cleaned, false, Collections.emptyList())
        );
        assertEquals("LFM3\n" + cleaned + "\n\n", flat);
        assertFalse(flat.contains("<think>"));
        assertFalse(flat.contains("</think>"));
    }
}
