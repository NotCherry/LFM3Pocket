package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Documents the start-path contract extracted in a3031ea: prefer a plain
 * {@code startService} so the foreground-service deadline is not armed while
 * the first frame still owns the main thread. Fall back only when the process
 * is already background-restricted.
 */
public final class RuntimeServiceStarterTest {
    @Test public void preferPlainStartWhileForegroundEligible() {
        assertTrue(RuntimeServiceStarter.preferPlainStart(false));
    }

    @Test public void fallBackWhenPlainStartIsRefused() {
        assertFalse(RuntimeServiceStarter.preferPlainStart(true));
    }
}
