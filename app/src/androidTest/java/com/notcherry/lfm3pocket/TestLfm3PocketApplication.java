package com.notcherry.lfm3pocket;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    private final AtomicInteger playbackOpened=new AtomicInteger(),playbackClosed=new AtomicInteger();
    private volatile CountDownLatch playbackStarted=new CountDownLatch(1);
    private volatile CountDownLatch playbackReleased=new CountDownLatch(1);
    private volatile boolean holdPlayback;
    private volatile CountDownLatch releasePlayback=new CountDownLatch(0);
    public void resetPlayback(){
        playbackOpened.set(0);playbackClosed.set(0);holdPlayback=false;
        playbackStarted=new CountDownLatch(1);playbackReleased=new CountDownLatch(1);
        releasePlayback=new CountDownLatch(0);
    }
    public void holdPlayback(){holdPlayback=true;releasePlayback=new CountDownLatch(1);playbackStarted=new CountDownLatch(1);}
    public void releasePlayback(){releasePlayback.countDown();}
    public boolean awaitPlaybackStarted()throws InterruptedException{return playbackStarted.await(10,TimeUnit.SECONDS);}
    public boolean awaitPlaybackReleased()throws InterruptedException{return playbackReleased.await(10,TimeUnit.SECONDS);}
    public int playbackOpened(){return playbackOpened.get();}
    @Override protected PlaybackController.Player createPlaybackPlayer(){
        playbackOpened.incrementAndGet();
        return new PlaybackController.Player(){
            private boolean playing;
            public void start(short[] pcm){playing=true;playbackStarted.countDown();}
            public int write(short[] pcm,int offset,int count)throws Exception{
                if(holdPlayback){
                    if(!releasePlayback.await(15,TimeUnit.SECONDS))return -1;
                }
                return count;
            }
            public void stop(){playing=false;}
            public void close(){playbackClosed.incrementAndGet();playbackReleased.countDown();}
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
        private final AtomicInteger assistantTurns = new AtomicInteger();
        private final AtomicInteger asrCalls = new AtomicInteger();
        private final AtomicInteger speechCalls = new AtomicInteger();
        private volatile CountDownLatch releaseFirstTurn = new CountDownLatch(1);
        private volatile CountDownLatch releaseBeforeFirstToken = new CountDownLatch(0);
        private volatile CountDownLatch generationStarted = new CountDownLatch(0);
        private volatile boolean stopRequested;
        private volatile boolean failNextGeneration;
        private volatile boolean allowFirstTurnSuccess;
        private volatile String loadedPath;
        private volatile InferenceSettings configured=InferenceSettings.DEFAULT;
        @Override public void configure(InferenceSettings settings) { configured=settings; }
        public InferenceSettings configuredSettings() { return configured; }
        private volatile boolean failLoad;
        public void failNextLoad() { failLoad = true; }
        private volatile String lastPrompt;
        private volatile String lastAsrPrompt;
        private volatile String lastMediaPath;
        private volatile List<String> lastMediaPaths=Collections.emptyList();
        private final List<List<String>> mediaByCall=Collections.synchronizedList(new ArrayList<>());
        public String lastPrompt() { return lastPrompt; }
        public String lastAsrPrompt() { return lastAsrPrompt; }
        public int asrCallCount() { return asrCalls.get(); }
        public int speechCallCount() { return speechCalls.get(); }
        public String lastMediaPath() { return lastMediaPath; }
        public List<String> lastMediaPaths() { return lastMediaPaths; }
        public List<List<String>> mediaByCall() { return new ArrayList<>(mediaByCall); }

        public void resetForTest() {
            turns.set(0);
            assistantTurns.set(0);
            asrCalls.set(0);
            speechCalls.set(0);
            stopRequested = false;
            loadedPath = null;
            failLoad = false;
            lastPrompt = null;
            lastAsrPrompt = null;
            lastMediaPath = null;
            lastMediaPaths = Collections.emptyList();
            mediaByCall.clear();
            releaseFirstTurn = new CountDownLatch(1);
            releaseBeforeFirstToken = new CountDownLatch(0);
            generationStarted = new CountDownLatch(0);
            failNextGeneration = false;
            allowFirstTurnSuccess = false;
        }

        public void holdFirstToken() {
            releaseBeforeFirstToken = new CountDownLatch(1);
            generationStarted = new CountDownLatch(1);
        }
        public void allowFirstTurnSuccess() {
            allowFirstTurnSuccess = true;
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
            return generate(handle, prompt,
                    mediaPath == null || mediaPath.isEmpty()
                            ? Collections.emptyList() : Collections.singletonList(mediaPath),
                    maxTokens, sink);
        }

        @Override public String generate(long handle, String prompt, List<String> mediaPaths,
                                         int maxTokens, TokenSink sink) {
            List<String> paths = mediaPaths == null ? Collections.emptyList() : new ArrayList<>(mediaPaths);
            lastMediaPaths = paths;
            lastMediaPath = paths.isEmpty() ? null : paths.get(paths.size() - 1);
            mediaByCall.add(paths);
            turns.incrementAndGet();
            if(prompt.contains("Perform ASR.")) {
                asrCalls.incrementAndGet();lastAsrPrompt=prompt;
                sink.onToken("Captured test speech");
                return null;
            }
            lastPrompt = prompt;
            int turn = assistantTurns.incrementAndGet();
            if (failNextGeneration) {
                failNextGeneration=false;
                return "Injected inference failure";
            }
            if (turn == 1 && !allowFirstTurnSuccess) {
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
            generationStarted.countDown();
            sink.onToken(turn == 1 ? "FAKE_STREAM_BEGIN" : "FAKE_RETRY_COMPLETE");
            if (allowFirstTurnSuccess && turn == 1) sink.onToken(" and multimodal follow-up ready");
            return null;
        }

        @Override public String synthesizeSpeech(long handle, String text, File outputWav) {
            speechCalls.incrementAndGet();
            try {
                if (outputWav.getParentFile() != null
                        && !outputWav.getParentFile().isDirectory()
                        && !outputWav.getParentFile().mkdirs()) {
                    return "Fixture speech directory missing";
                }
                int samples = Math.max(1, SpeechAudio.SAMPLE_RATE / 8);
                short[] pcm = new short[samples];
                double step = 2.0 * Math.PI * 440.0 / SpeechAudio.SAMPLE_RATE;
                for (int i = 0; i < samples; i++) pcm[i] = (short) (Math.sin(step * i) * 12000);
                SpeechAudio.writeWav(outputWav, pcm);
                return null;
            } catch (Exception e) {
                return "Fixture speech failed: " + e.getMessage();
            }
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
