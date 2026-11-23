package com.notcherry.lfm3pocket;

import org.junit.Rule;
import org.junit.rules.Timeout;

/** Base for JVM unit tests that synchronize threads or block on latches (deadlock-sensitive). */
public abstract class HanamiConcurrencyUnitTest {
    @Rule
    public final Timeout perTestTimeout = TestTimeouts.CONCURRENCY_DEFAULT;
}