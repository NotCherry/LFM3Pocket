package com.notcherry.lfm3pocket;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Isolated instrumentation-only application. No model files or JNI are used for UI E2E. */
public final class TestLfm3PocketApplication extends Lfm3PocketApplication {
    private volatile boolean capturePermission;
    public void capturePermission(boolean value){capturePermission=value;}
    @Override public boolean hasMicrophonePermission(){return capturePermission || super.hasMicrophonePermission();}
    private final AtomicInteger captureOpened=new AtomicInteger(),captureClosed=new AtomicInteger();
    private volatile boolean shortCapture;
    private volatile CountDownLatch captured=new CountDownLatch(1),captureReleased=new CountDownLatch(1);
    public void resetCapture(boolean tooShort){shortCapture=tooShort;captureOpened.set(0);captureClosed.set(0);captured=new CountDownLatch(1);captureReleased=new CountDownLatch(1);}
    public int captureOpened(){return captureOpened.get();}
    public void shortCapture(boolean value){shortCapture=value;captured=new CountDownLatch(1);}
    public boolean awaitCaptured()throws InterruptedException{return captured.await(10,TimeUnit.SECONDS);}
    public boolean awaitCaptureReleased()throws InterruptedException{return captureReleased.await(10,TimeUnit.SECONDS);}
    @Override protected RecordingController.Source createRecordingSource(){
        captureOpened.incrementAndGet();
        return new RecordingController.Source(){
            private boolean delivered;
            public void start(){}
            public int read(short[] buffer){
                if(shortCapture || delivered)return 0;
                java.util.Arrays.fill(buffer,(short)1200);delivered=true;captured.countDown();return buffer.length;
            }
            public void close(){captureClosed.incrementAndGet();captureReleased.countDown();}
        };
    }
    private volatile ScriptedInferenceEngine scripted = new ScriptedInferenceEngine();

    @Override protected InferenceEngine createInferenceEngine() {
        scripted = new ScriptedInferenceEngine();
        return scripted;
    }

    @Override protected AndroidActionExecutor createActionExecutor() {
        return (String name, Map<String, String> args) ->
                new AndroidActionExecutor.ActionResult(false, "TEST_DEFAULT_DENY", name);
    }

    private final AtomicInteger toolCalls=new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong toolTime=new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean failTool;
    public void resetTools() { toolCalls.set(0);toolTime.set(0);failTool=false; }
    public int toolCalls() { return toolCalls.get(); }
    public void failTool(boolean value) { failTool=value; }
    public void advanceToolTime(long millis) { toolTime.addAndGet(millis); }
    @Override protected ToolCoordinator.Handler createToolHandler() {
        return request -> {
            toolCalls.incrementAndGet();
            return new AndroidActionExecutor.ActionResult(!failTool,
                    failTool ? "TEST_FAILED" : "TEST_PERFORMED", failTool ? "Test handler failed" : "Test handler executed");
        };
    }
    @Override protected java.util.function.Predicate<ToolRegistry.Request> createToolPermissionCheck() { return request -> true; }
    @Override protected java.util.function.LongSupplier createToolClock() { return toolTime::get; }

    public ScriptedInferenceEngine scriptedEngine() {
        return scripted;
    }

    public static final class ScriptedInferenceEngine implements InferenceEngine {
        private final AtomicInteger turns = new AtomicInteger();
        private volatile CountDownLatch releaseFirstTurn = new CountDownLatch(1);
        private volatile boolean stopRequested;
        private volatile String loadedPath;
        private volatile InferenceSettings configured=InferenceSettings.DEFAULT;
        @Override public void configure(InferenceSettings settings) { configured=settings; }
        public InferenceSettings configuredSettings() { return configured; }
        private volatile boolean failLoad;
        public void failNextLoad() { failLoad = true; }
        private volatile String lastPrompt;
        public String lastPrompt() { return lastPrompt; }

        public void resetForTest() {
            turns.set(0);
            stopRequested = false;
            loadedPath = null;
            failLoad = false;
            lastPrompt = null;
            releaseFirstTurn = new CountDownLatch(1);
        }

        public int callCount() { return turns.get(); }
        public String loadedPath() { return loadedPath; }

        @Override public long loadModel(String modelPath, String projectorPath) {
            if (failLoad) { failLoad = false; return 0; }
            loadedPath = modelPath;
            return 13L; // Test-only fake handle; deliberately never opens the dummy file.
        }

        @Override public void unloadModel(long handle) { }

        @Override public String generate(long handle, String prompt, String mediaPath,
                                         int maxTokens, TokenSink sink) {
            lastPrompt = prompt;
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
