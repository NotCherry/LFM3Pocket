package com.notcherry.lfm3pocket;

import org.junit.Rule;
import org.junit.rules.Timeout;

/**
 * Base for JVM unit tests that synchronize threads or block on latches (deadlock-sensitive).
 *
 * <p>Do not use for {@link RuntimePolicyTest}: {@code RuntimePolicy} must be invoked on its owner
 * thread, and {@link Timeout} runs each test on a worker thread.
 */
public abstract class HanamiConcurrencyUnitTest {
    @Rule
    public final Timeout perTestTimeout = TestTimeouts.CONCURRENCY_DEFAULT;
}
