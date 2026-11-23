package com.notcherry.lfm3pocket;

import org.junit.Rule;
import org.junit.rules.Timeout;

/**
 * Optional base for JVM unit tests that need the shared {@link TestTimeouts#UNIT_DEFAULT}
 * ceiling. Concurrency-heavy suites apply {@link TestTimeouts#CONCURRENCY_DEFAULT} instead
 * (see subclasses or direct {@code @Rule} usage).
 */
public abstract class HanamiUnitTestBase {
    @Rule
    public final Timeout perTestTimeout = TestTimeouts.UNIT_DEFAULT;
}