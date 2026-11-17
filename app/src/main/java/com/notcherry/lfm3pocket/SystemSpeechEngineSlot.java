package com.notcherry.lfm3pocket;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks the active system TTS instance so late teardown cannot shut down a newer engine. */
final class SystemSpeechEngineSlot<E> {
    interface MainPoster {
        void post(Runnable task);
    }

    interface EngineOps<E> {
        void stopEngine(E engine);
        void shutdownEngine(E engine);
    }

    private final Object lock = new Object();
    private final MainPoster main;
    private final EngineOps<E> ops;
    private final Set<Long> cancelledSessions = ConcurrentHashMap.newKeySet();
    private volatile E engine;
    private volatile long activeSessionId;

    SystemSpeechEngineSlot(MainPoster main, EngineOps<E> ops) {
        this.main = main;
        this.ops = ops;
    }

    E currentEngine() {
        return engine;
    }

    long activeSessionId() {
        return activeSessionId;
    }

    void cancelSession(long sessionId) {
        cancelledSessions.add(sessionId);
    }

    boolean isSessionCancelled(long sessionId) {
        return cancelledSessions.contains(sessionId);
    }

    E prepareNewEngine(long sessionId, E created) {
        synchronized (lock) {
            if (cancelledSessions.contains(sessionId)) {
                ops.shutdownEngine(created);
                return null;
            }
            E previous = engine;
            if (previous != null) {
                ops.shutdownEngine(previous);
            }
            engine = created;
            activeSessionId = sessionId;
        }
        return created;
    }

    /** Shut down only {@code victim}; clear the field only when it still refers to {@code victim}. */
    void shutdownOnMain(E victim, long sessionId, Runnable clearSessionSpeaking) {
        cancelSession(sessionId);
        main.post(() -> {
            synchronized (lock) {
                E target = victim;
                if (target == null && activeSessionId == sessionId) {
                    target = engine;
                }
                if (target != null) {
                    ops.stopEngine(target);
                    ops.shutdownEngine(target);
                }
                if (target != null && engine == target) {
                    engine = null;
                }
            }
            boolean clearFlags = activeSessionId == sessionId;
            if (!clearFlags && cancelledSessions.remove(sessionId)) {
                clearFlags = true;
            } else if (clearFlags) {
                cancelledSessions.remove(sessionId);
            }
            if (clearFlags) {
                clearSessionSpeaking.run();
            }
        });
    }
}