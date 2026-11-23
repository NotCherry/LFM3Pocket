package com.notcherry.lfm3pocket;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runners.model.TestTimedOutException;

/** Proves the shared {@link Timeout} rule actually interrupts hung tests. */
public final class TestTimeoutsSelfTest {
    @Rule
    public final Timeout perTestTimeout = TestTimeouts.SELF_CHECK;

    @Test(expected = TestTimedOutException.class)
    public void hangingTestIsInterruptedByTimeoutRule() throws Exception {
        Thread.sleep(60_000L);
    }
}