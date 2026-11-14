package com.notcherry.lfm3pocket;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class FlatTranscriptTest {
    @Test public void emptySnapshotIsPlaceholder() {
        assertEquals("No conversation yet.\n",
                FlatTranscript.format(new ChatSnapshot(Collections.emptyList(), "", false)));
    }

    @Test public void multiTurnAndSourcesMatchMarkers() {
        ChatSnapshot.Turn user = new ChatSnapshot.Turn("user", "Hi", null, Collections.emptyList(), false);
        ChatSnapshot.Turn assistant = new ChatSnapshot.Turn("assistant", "Hello", "secret plan",
                Collections.singletonList(new ChatSnapshot.Source("Doc", "https://example.com", "snip")),
                false);
        String flat = FlatTranscript.format(new ChatSnapshot(Arrays.asList(user, assistant), "done", false));
        assertTrue(flat.startsWith("YOU\nHi\n\n"));
        assertTrue(flat.contains("LFM3\nHello\n\n"));
        assertTrue(flat.contains("[1] Doc\nhttps://example.com\n"));
        assertFalse("Reasoning must not appear in flat v1", flat.contains("secret plan"));
        assertFalse(flat.contains("<think>"));
    }

    @Test public void streamingAppendsCursor() {
        ChatSnapshot.Turn user = new ChatSnapshot.Turn("user", "Q", null, Collections.emptyList(), false);
        ChatSnapshot.Turn stream = new ChatSnapshot.Turn("assistant", "Partial", null,
                Collections.emptyList(), true);
        String flat = FlatTranscript.format(new ChatSnapshot(Arrays.asList(user, stream), "gen", true));
        assertTrue(flat.contains("YOU\nQ\n\n"));
        assertTrue(flat.endsWith("LFM3\nPartial▌"));
    }
}
