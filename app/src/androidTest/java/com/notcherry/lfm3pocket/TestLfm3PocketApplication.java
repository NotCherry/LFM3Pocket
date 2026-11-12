package com.notcherry.lfm3pocket;

import java.util.Map;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Isolated instrumentation-only application. No model files or JNI are used for UI E2E. */
public final class TestLfm3PocketApplication extends Lfm3PocketApplication {
    private final AtomicInteger searchCalls=new AtomicInteger();
    private volatile boolean failSearch;
    private volatile boolean holdSearch;
    private volatile CountDownLatch searchStarted=new CountDownLatch(0),searchReleased=new CountDownLatch(0);
    public void resetSearch() {
        searchCalls.set(0);failSearch=false;holdSearch=false;
        searchStarted=new CountDownLatch(1);searchReleased=new CountDownLatch(0);
    }
    public void failSearch(boolean value){failSearch=value;}
    public void holdSearch() {
        holdSearch=true;searchStarted=new CountDownLatch(1);searchReleased=new CountDownLatch(1);
    }
    public boolean awaitSearchStarted()throws InterruptedException{return searchStarted.await(10,TimeUnit.SECONDS);}
    public void releaseSearch(){searchReleased.countDown();}
    public int searchCalls(){return searchCalls.get();}
    @Override protected ChatSession.Search createWebSearch() {
        return (endpoint,question)->{
            searchCalls.incrementAndGet();searchStarted.countDown();
            if(holdSearch&&!searchReleased.await(10,TimeUnit.SECONDS))
                throw new Exception("Fake search timed out");
            if(failSearch)throw new Exception("Injected search failure");
            return Collections.singletonList(new WebSearch.Source(
                    "Fake result","Reference snippet","https://example.test/result"));
        };
    }

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
    private volatile boolean denyToolPermission;
    private volatile ToolRegistry.Request lastToolRequest;
    public void resetTools() { toolCalls.set(0);toolTime.set(0);failTool=false;denyToolPermission=false;lastToolRequest=null; }
    public int toolCalls() { return toolCalls.get(); }
    public ToolRegistry.Request lastToolRequest() { return lastToolRequest; }
    public void failTool(boolean value) { failTool=value; }
    public void denyToolPermission(boolean value) { denyToolPermission=value; }
    public void advanceToolTime(long millis) { toolTime.addAndGet(millis); }
    @Override protected ToolCoordinator.Handler createToolHandler() {
        return request -> {
            toolCalls.incrementAndGet();
            lastToolRequest=request;
            if(request instanceof ToolRegistry.TimerRequest && !failTool) {
                ToolRegistry.TimerRequest timer=(ToolRegistry.TimerRequest)request;
                return new AndroidActionExecutor.ActionResult(true,"TEST_TIMER_REQUEST_OPENED",
                        "Timer request sent to the Android clock for "+timer.seconds+" seconds. Check the clock app to verify it started.");
            }
            if(request instanceof ToolRegistry.AlarmRequest && !failTool) {
                ToolRegistry.AlarmRequest alarm=(ToolRegistry.AlarmRequest)request;
                return new AndroidActionExecutor.ActionResult(true,"TEST_ALARM_REQUEST_OPENED",
                        String.format(java.util.Locale.ROOT,"Alarm request sent to the Android clock for %02d:%02d. Check Clock to verify it was set.",alarm.hour,alarm.minute));
            }
            return new AndroidActionExecutor.ActionResult(!failTool,
                    failTool ? "TEST_FAILED" : "TEST_PERFORMED", failTool ? "Test handler failed" : "Test handler executed");
        };
    }
    @Override protected java.util.function.Predicate<ToolRegistry.Request> createToolPermissionCheck() { return request -> !denyToolPermission; }
    @Override protected java.util.function.LongSupplier createToolClock() { return toolTime::get; }

    public ScriptedInferenceEngine scriptedEngine() {
        return scripted;
    }

    public static final class ScriptedInferenceEngine implements InferenceEngine {
        private final AtomicInteger turns = new AtomicInteger();
        private volatile CountDownLatch releaseFirstTurn = new CountDownLatch(1);
        private volatile CountDownLatch releaseBeforeFirstToken = new CountDownLatch(0);
        private volatile CountDownLatch generationStarted = new CountDownLatch(0);
        private volatile boolean stopRequested;
        private volatile boolean failNextGeneration;
        private volatile String loadedPath;
        private volatile InferenceSettings configured=InferenceSettings.DEFAULT;
        @Override public void configure(InferenceSettings settings) { configured=settings; }
        public InferenceSettings configuredSettings() { return configured; }
        private volatile boolean failLoad;
        public void failNextLoad() { failLoad = true; }
        private volatile String lastPrompt;
        private volatile String lastMediaPath;
        public String lastPrompt() { return lastPrompt; }
        public String lastMediaPath() { return lastMediaPath; }

        public void resetForTest() {
            turns.set(0);
            stopRequested = false;
            loadedPath = null;
            failLoad = false;
            lastPrompt = null;
            lastMediaPath = null;
            releaseFirstTurn = new CountDownLatch(1);
            releaseBeforeFirstToken = new CountDownLatch(0);
            generationStarted = new CountDownLatch(0);
            failNextGeneration = false;
        }

        public void holdFirstToken() {
            releaseBeforeFirstToken = new CountDownLatch(1);
            generationStarted = new CountDownLatch(1);
        }
        public boolean awaitGenerationStarted() throws InterruptedException {
            return generationStarted.await(10,TimeUnit.SECONDS);
        }
        public void releaseFirstToken() { releaseBeforeFirstToken.countDown(); }
        public void completeFirstTurn() { releaseFirstTurn.countDown(); }
        public void failNextGeneration() { failNextGeneration=true; }

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
            lastMediaPath = mediaPath;
            int turn = turns.incrementAndGet();
            if (failNextGeneration) {
                failNextGeneration=false;
                return "Injected inference failure";
            }
            if (turn == 1) {
                generationStarted.countDown();
                try {
                    if (!releaseBeforeFirstToken.await(10,TimeUnit.SECONDS)) return "FAKE_FIRST_TOKEN_TIMEOUT";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "FAKE_INTERRUPTED_BEFORE_TOKEN";
                }
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
