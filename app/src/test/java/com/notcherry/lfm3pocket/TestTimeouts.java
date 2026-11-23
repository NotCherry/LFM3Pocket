package com.notcherry.lfm3pocket;

import java.util.concurrent.TimeUnit;

import org.junit.rules.Timeout;

/** Shared JUnit 4 per-test timeouts for JVM unit tests. See {@code docs/TESTING.md}. */
public final class TestTimeouts {
    /**
     * Per-method ceiling for deadlock-sensitive JVM tests where the rule may run the test body on a
     * worker thread (safe when policy owner-thread rules do not apply to the test thread).
     */
    public static final Timeout CONCURRENCY_DEFAULT = Timeout.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withLookingForStuckThread(true)
            .build();

    /** {@link VoiceUnloadLifecycleTest} loops unload causes with timed waits; keep above 30s JVM. */
    public static final int VOICE_UNLOAD_LIFECYCLE_MS = 90_000;

    /** {@link RuntimePolicyTest#serializedInterleavingsKeepForegroundAndWakeEffectsConsistent}. */
    public static final int RUNTIME_POLICY_INTERLEAVING_MS = 30_000;

    /** Self-check only — no stuck-thread lookup (simpler failure type). */
    public static final Timeout SELF_CHECK_RULE =
            Timeout.builder().withTimeout(2, TimeUnit.SECONDS).build();

    private TestTimeouts() {}
}
