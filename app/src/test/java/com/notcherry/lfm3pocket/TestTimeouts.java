package com.notcherry.lfm3pocket;

import java.util.concurrent.TimeUnit;

import org.junit.rules.Timeout;

/** Shared JUnit 4 per-test timeouts for JVM unit tests. See {@code docs/TESTING.md}. */
public final class TestTimeouts {
    /** Default ceiling for ordinary JVM unit tests when applied via {@link HanamiUnitTestBase}. */
    public static final Timeout UNIT_DEFAULT = Timeout.builder()
            .withTimeout(2, TimeUnit.MINUTES)
            .withLookingForStuckThread(true)
            .build();

    /** Concurrency / deadlock-sensitive JVM tests (voice session, runtime policy, loopback). */
    public static final Timeout CONCURRENCY_DEFAULT = Timeout.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withLookingForStuckThread(true)
            .build();

    /** Short bound used only by {@link TestTimeoutsSelfTest}. */
    public static final Timeout SELF_CHECK = Timeout.builder()
            .withTimeout(2, TimeUnit.SECONDS)
            .withLookingForStuckThread(true)
            .build();

    private TestTimeouts() {}
}