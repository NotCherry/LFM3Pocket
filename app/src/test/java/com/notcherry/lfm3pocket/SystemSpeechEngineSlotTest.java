package com.notcherry.lfm3pocket;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Behavioral guard: Stop-then-Speak must not shut down a newer engine instance. */
public class SystemSpeechEngineSlotTest {
    static final class FakeEngine {
        boolean stopCalled;
        boolean shutdownCalled;
    }

    private SystemSpeechEngineSlot<FakeEngine> slotWithDeferredMain(Queue<Runnable> deferred) {
        List<FakeEngine> shutdownOrder = new ArrayList<>();
        return new SystemSpeechEngineSlot<>(deferred::add, new SystemSpeechEngineSlot.EngineOps<FakeEngine>() {
            @Override public void stopEngine(FakeEngine engine) {
                engine.stopCalled = true;
            }
            @Override public void shutdownEngine(FakeEngine engine) {
                engine.shutdownCalled = true;
                shutdownOrder.add(engine);
            }
        });
    }

    private void drain(Queue<Runnable> deferred) {
        while (!deferred.isEmpty()) deferred.remove().run();
    }

    @Test
    public void lateShutdownFromStoppedSessionDoesNotShutdownNewEngine() {
        Queue<Runnable> deferred = new ArrayDeque<>();
        SystemSpeechEngineSlot<FakeEngine> slot = slotWithDeferredMain(deferred);
        FakeEngine first = new FakeEngine();
        FakeEngine second = new FakeEngine();
        slot.prepareNewEngine(1, first);
        slot.shutdownOnMain(first, 1, () -> { });
        assertTrue("late shutdown must not run before main drain", deferred.size() == 1);

        slot.prepareNewEngine(2, second);
        assertSame(second, slot.currentEngine());
        assertFalse(first.shutdownCalled);
        assertFalse(second.shutdownCalled);

        drain(deferred);

        assertTrue(first.shutdownCalled);
        assertFalse("new engine must survive previous session teardown", second.shutdownCalled);
        assertSame(second, slot.currentEngine());
    }

    @Test
    public void stopThenImmediateSpeakLeavesNewEngineIntactAfterOldTeardown() {
        Queue<Runnable> deferred = new ArrayDeque<>();
        SystemSpeechEngineSlot<FakeEngine> slot = slotWithDeferredMain(deferred);
        FakeEngine stopped = slot.prepareNewEngine(10, new FakeEngine());
        slot.shutdownOnMain(stopped, 10, () -> { });

        FakeEngine replacement = new FakeEngine();
        slot.prepareNewEngine(11, replacement);
        assertEquals(1, deferred.size());
        drain(deferred);

        assertTrue(stopped.shutdownCalled);
        assertFalse(replacement.shutdownCalled);
        assertSame(replacement, slot.currentEngine());
    }
}