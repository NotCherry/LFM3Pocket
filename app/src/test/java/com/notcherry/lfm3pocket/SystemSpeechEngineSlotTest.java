package com.notcherry.lfm3pocket;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
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

    @Test
    public void nullVictimShutdownBeforePrepareClearsCancelledSession() {
        Queue<Runnable> deferred = new ArrayDeque<>();
        SystemSpeechEngineSlot<FakeEngine> slot = slotWithDeferredMain(deferred);
        AtomicBoolean speakingCleared = new AtomicBoolean();
        slot.cancelSession(5);
        slot.shutdownOnMain(null, 5, () -> speakingCleared.set(true));
        assertNull(slot.currentEngine());
        drain(deferred);
        assertTrue("cancelled session must not leave speaking stuck", speakingCleared.get());
        assertNull(slot.currentEngine());
    }

    @Test
    public void nullVictimShutdownAfterPrepareShutsEngineAndClears() {
        Queue<Runnable> deferred = new ArrayDeque<>();
        SystemSpeechEngineSlot<FakeEngine> slot = slotWithDeferredMain(deferred);
        FakeEngine engine = new FakeEngine();
        slot.prepareNewEngine(7, engine);
        AtomicBoolean speakingCleared = new AtomicBoolean();
        slot.shutdownOnMain(null, 7, () -> speakingCleared.set(true));
        assertFalse(engine.shutdownCalled);
        drain(deferred);
        assertTrue(engine.shutdownCalled);
        assertTrue(speakingCleared.get());
        assertNull(slot.currentEngine());
    }

    @Test
    public void prepareNewEngineAfterCancelShutsDownWithoutAdopting() {
        SystemSpeechEngineSlot<FakeEngine> slot = slotWithDeferredMain(new ArrayDeque<>());
        slot.cancelSession(3);
        FakeEngine created = new FakeEngine();
        FakeEngine adopted = slot.prepareNewEngine(3, created);
        assertNull(adopted);
        assertTrue(created.shutdownCalled);
        assertNull(slot.currentEngine());
        assertNotSame(created, slot.currentEngine());
    }

    @Test
    public void lateTeardownOfCancelledSessionDoesNotClearNewSessionSpeakingState() {
        Queue<Runnable> deferred = new ArrayDeque<>();
        SystemSpeechEngineSlot<FakeEngine> slot = slotWithDeferredMain(deferred);
        final long[] currentSession = {1};
        final boolean[] speaking = {true};

        // Session A starts speaking but is cancelled before preparing an engine.
        slot.shutdownOnMain(null, 1, () -> {
            if (currentSession[0] == 1) speaking[0] = false;
        });
        assertEquals(1, deferred.size());

        // Session B becomes the active session and starts speaking.
        FakeEngine engineB = slot.prepareNewEngine(2, new FakeEngine());
        assertSame(engineB, slot.currentEngine());
        currentSession[0] = 2;
        speaking[0] = true;

        // Draining A's late teardown must not clear B's speaking state.
        drain(deferred);
        assertTrue("new session must remain speaking after old session teardown", speaking[0]);
        assertSame(engineB, slot.currentEngine());
        assertFalse(engineB.shutdownCalled);
    }

    @Test
    public void cancellingCurrentSessionStillClearsItsSpeakingState() {
        Queue<Runnable> deferred = new ArrayDeque<>();
        SystemSpeechEngineSlot<FakeEngine> slot = slotWithDeferredMain(deferred);
        FakeEngine engine = slot.prepareNewEngine(4, new FakeEngine());
        final long[] currentSession = {4};
        final boolean[] speaking = {true};
        slot.shutdownOnMain(engine, 4, () -> {
            if (currentSession[0] == 4) speaking[0] = false;
        });
        drain(deferred);
        assertTrue(engine.shutdownCalled);
        assertFalse(speaking[0]);
        assertNull(slot.currentEngine());
    }
}