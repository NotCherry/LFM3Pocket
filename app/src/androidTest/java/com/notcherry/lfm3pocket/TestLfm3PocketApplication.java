package com.notcherry.lfm3pocket;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Isolated instrumentation-only application. No model files or JNI are used for UI E2E. */
public final class TestLfm3PocketApplication extends Lfm3PocketApplication {
    private volatile ScriptedInferenceEngine scripted = new ScriptedInferenceEngine();

    @Override protected InferenceEngine createInferenceEngine() {
        scripted = new ScriptedInferenceEngine();
        return scripted;
    }

    @Override protected AndroidActionExecutor createActionExecutor() {
        return (String name, Map<String, String> args) ->
                new AndroidActionExecutor.ActionResult(false, "TEST_DEFAULT_DENY", name);
    }

    public ScriptedInferenceEngine scriptedEngine() {
        return scripted;
    }

    public static final class ScriptedInferenceEngine implements InferenceEngine {
        private final AtomicInteger turns = new AtomicInteger();
        private volatile CountDownLatch releaseFirstTurn = new CountDownLatch(1);
        private volatile boolean stopRequested;
        private volatile String loadedPath;

        public void resetForTest() {
            turns.set(0);
            stopRequested = false;
            loadedPath = null;
            releaseFirstTurn = new CountDownLatch(1);
        }

        public int callCount() { return turns.get(); }
        public String loadedPath() { return loadedPath; }

        @Override public long loadModel(String modelPath, String projectorPath) {
            loadedPath = modelPath;
            return 13L; // Test-only fake handle; deliberately never opens the dummy file.
        }

        @Override public void unloadModel(long handle) { }

        @Override public String generate(long handle, String prompt, String mediaPath,
                                         int maxTokens, TokenSink sink) {
            int turn = turns.incrementAndGet();
            if (turn == 1) {
                sink.onToken("FAKE_STREAM_BEGIN");
                try {
                    if (!releaseFirstTurn.await(15, TimeUnit.SECONDS)) return "FAKE_CANCEL_TIMEOUT";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "FAKE_INTERRUPTED";
                }
                return stopRequested ? null : "FAKE_NOT_CANCELLED";
            }
            sink.onToken("FAKE_RETRY_COMPLETE");
            return null;
        }

        @Override public void stop() {
            stopRequested = true;
            releaseFirstTurn.countDown();
        }

        @Override public void resetStop() {
            stopRequested = false;
        }
    }
}
