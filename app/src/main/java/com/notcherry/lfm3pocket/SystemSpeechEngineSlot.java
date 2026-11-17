package com.notcherry.lfm3pocket;

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

    E prepareNewEngine(long sessionId, E created) {
        synchronized (lock) {
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
        main.post(() -> {
            synchronized (lock) {
                if (victim != null) {
                    ops.stopEngine(victim);
                    ops.shutdownEngine(victim);
                }
                if (engine == victim) {
                    engine = null;
                }
            }
            if (activeSessionId == sessionId) {
                clearSessionSpeaking.run();
            }
        });
    }
}