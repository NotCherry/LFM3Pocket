package com.notcherry.lfm3pocket;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

/**
 * Documents the prefix/reuse policy used by native.cpp (blocking review #3).
 * Full hybrid seq_rm behaviour needs a real LFM3 GGUF; this locks the pure rules.
 */
public class KvPrefixReuseTest {
    static int commonPrefix(List<Integer> a, List<Integer> b) {
        int n = Math.min(a.size(), b.size());
        int i = 0;
        while (i < n && a.get(i).equals(b.get(i))) i++;
        return i;
    }

    /** When the new prompt equals the cached prompt, re-eval the last token for logits. */
    static int reuseStart(List<Integer> cached, List<Integer> prompt) {
        int shared = commonPrefix(cached, prompt);
        if (shared == prompt.size() && shared > 0) shared -= 1;
        return shared;
    }

    @Test public void appendOnlyTurnReusesFullCache() {
        List<Integer> cached = Arrays.asList(1, 2, 3, 4, 5);
        List<Integer> next = Arrays.asList(1, 2, 3, 4, 5, 6, 7);
        assertEquals(5, reuseStart(cached, next));
    }

    @Test public void identicalPromptReevalsLastToken() {
        List<Integer> cached = Arrays.asList(1, 2, 3);
        assertEquals(2, reuseStart(cached, cached));
    }

    @Test public void editedHistoryForcesEarlierSharedPrefix() {
        List<Integer> cached = Arrays.asList(1, 2, 3, 9, 9);
        List<Integer> next = Arrays.asList(1, 2, 3, 4, 5);
        assertEquals(3, reuseStart(cached, next));
    }

    @Test public void emptyCacheStartsAtZero() {
        assertEquals(0, reuseStart(Collections.emptyList(), Arrays.asList(1, 2)));
    }
}
