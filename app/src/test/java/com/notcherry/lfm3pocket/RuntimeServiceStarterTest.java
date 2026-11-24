package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * Documents the start-path contract extracted in a3031ea: prefer a plain
 * {@code startService} so the foreground-service deadline is not armed while
 * the first frame still owns the main thread. Fall back only when the process
 * is already background-restricted, and survive Android 12+/14+ FGS refusals.
 */
public final class RuntimeServiceStarterTest {
    @Test public void preferPlainStartWhileForegroundEligible() {
        assertTrue(RuntimeServiceStarter.preferPlainStart(false));
    }

    @Test public void fallBackWhenPlainStartIsRefused() {
        assertFalse(RuntimeServiceStarter.preferPlainStart(true));
    }

    @Test public void starterFallsBackToForegroundServiceWhenPlainStartRefused() {
        AtomicInteger fgCalls = new AtomicInteger();
        boolean started = RuntimeServiceStarter.start(
                () -> { throw new IllegalStateException("background start not allowed"); },
                fgCalls::incrementAndGet,
                34);
        assertTrue(started);
        assertEquals(1, fgCalls.get());
    }

    @Test public void starterSurvivesForegroundServiceStartNotAllowed() {
        boolean started = RuntimeServiceStarter.start(
                () -> { throw new IllegalStateException("background start not allowed"); },
                () -> { throw new IllegalStateException("ForegroundServiceStartNotAllowedException"); },
                34);
        assertFalse(started);
    }

    @Test public void promoteDowngradesToSpecialUseWhenExtraFgsTypeDenied() {
        List<Integer> attempts = new ArrayList<>();
        boolean promoted = ModelRuntimeService.promoteWithFallback(
                34,
                0x40000080, // SPECIAL_USE | MICROPHONE
                0x40000000, // SPECIAL_USE
                type -> {
                    attempts.add(type);
                    if (type != 0x40000000) throw new SecurityException("mic FGS denied");
                },
                () -> { throw new AssertionError("legacy path should not run on API 34+"); });
        assertTrue(promoted);
        assertEquals(List.of(0x40000080, 0x40000000), attempts);
    }

    @Test public void promoteReturnsFalseInsteadOfCrashingWhenForegroundDenied() {
        boolean promoted = ModelRuntimeService.promoteWithFallback(
                34,
                0x40000000,
                0x40000000,
                type -> { throw new IllegalStateException("mAllowStartForeground false"); },
                () -> {});
        assertFalse(promoted);
    }

    @Test public void promoteLegacyPathCatchesForegroundDenial() {
        boolean promoted = ModelRuntimeService.promoteWithFallback(
                31,
                0,
                0,
                type -> { throw new AssertionError("API 34 path should not run on API 31"); },
                () -> { throw new IllegalStateException("mAllowStartForeground false"); });
        assertFalse(promoted);
    }
}
