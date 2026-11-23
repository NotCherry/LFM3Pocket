package com.notcherry.lfm3pocket;

import java.util.concurrent.TimeUnit;

import org.junit.rules.Timeout;

/** JUnit 4 per-test ceilings for on-device instrumentation. See {@code docs/TESTING.md}. */
public final class InstrumentedTestTimeouts {
    /** Typical UI / contract instrumentation tests on the emulator. */
    public static final Timeout DEFAULT = Timeout.builder()
            .withTimeout(5, TimeUnit.MINUTES)
            .withLookingForStuckThread(true)
            .build();

    /** JNI load/generate smoke paths that can take several minutes on CI hardware. */
    public static final Timeout HEAVY_JNI = Timeout.builder()
            .withTimeout(12, TimeUnit.MINUTES)
            .withLookingForStuckThread(true)
            .build();

    /** Matrix / stress / scale E2E that intentionally run longer. */
    public static final Timeout HEAVY_E2E = Timeout.builder()
            .withTimeout(15, TimeUnit.MINUTES)
            .withLookingForStuckThread(true)
            .build();

    /** Network listener instrumentation (same ceiling as JVM concurrency tests, scaled up). */
    public static final Timeout CONCURRENCY = Timeout.builder()
            .withTimeout(2, TimeUnit.MINUTES)
            .withLookingForStuckThread(true)
            .build();

    private InstrumentedTestTimeouts() {}
}