package com.notcherry.lfm3pocket;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Process-scoped model, conversation, and spoken playback. Activities attach as
 * viewers. Leaving the screen does not unload the weights or cancel a reply
 * unless the user turned keep-loaded off, or thermal / memory policy says so.
 */
public final class ModelRuntime implements ModelRuntimeVoiceCallbacks.Host {
    public interface Ui {
        void onChat(String transcript, String status, boolean generating);
        void onSnapshot(ChatSnapshot snapshot);
        void onAssistantReply(String text);
        void onHistoryTitle(String title);
        void onPlayback(String message);
        void onSpeech(boolean speaking, String status);
        void onToolOutcome(ToolRegistry.Request request, ToolCoordinator.Outcome outcome);
        void onVoiceStatus(String status);
        default void onModelStateChanged(boolean loaded, String status) {}
        boolean isUsable();
    }

    public interface LoadCallback {
        void finished(String statusText);
    }

    private final Lfm3PocketApplication app;
    private final RuntimePreferences preferences;
    private final RuntimePolicy policy = new RuntimePolicy();
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Foreground-priority infer thread (avoid default background nice on some devices). */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
            r.run();
        }, "hanami-infer");
        return t;
    });
    private final ExecutorService audioWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
            r.run();
        }, "hanami-audio");
        return t;
    });
    /** Offloads periodic Room partial-turn saves so SQLite writes never stall token decode. */
    private final ExecutorService partialSaveWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "hanami-db-partial");
        return t;
    });
    private final InferenceEngine engine;
    private final PlaybackController playback;
    private final AndroidPlaybackFocus playbackFocus;
    private HistoryRepository history;
    private ChatSession session;
    private Ui ui;
    private ModelRuntimeService service;
    private PowerManager.WakeLock wakeLock;
    private RuntimePolicy.Effects last;
    private volatile long handle;
    private volatile InferenceSettings activeSettings = InferenceSettings.DEFAULT;
    private volatile String loadedPath = "";
    private volatile String conversationId = "";
    private volatile String historyTitle;
    private volatile String lastAssistant = "";
    private volatile boolean speechCancel;
    private boolean playbackNoted;
    private boolean serviceStartRequested;
    private volatile boolean loading;
    private volatile boolean unloading;
    private long lastKvTrimReleaseMs;
    private String lastPolicyUnloadReason = "";
    private int sessionEpoch;
    private final AtomicInteger conversationEpoch = new AtomicInteger();
    private String pendingQuestion = "";
    private int pendingMax = 512;
    private String lastTranscript = "";
    private String lastStatus = "";
    private boolean lastGenerating;
    private ChatSnapshot lastSnapshot;
    private final AtomicInteger pendingTokens = new AtomicInteger();
    private final AtomicBoolean tokenFlushPosted = new AtomicBoolean();
    private final AtomicBoolean historyRecoveryScheduled = new AtomicBoolean();
    private final LoopbackAgentApi loopback;
    private final VoiceActionRuntime voiceActions;
    private final SpeechReplyRouter speechRouter;
    private final TinyVocaPackStore tinyVocaPack;
    private final AndroidSystemSpeechSynthesizer systemSpeech;
    private final Runnable idleTick = new Runnable() {
        @Override public void run() {
            dispatchPolicy(() -> {
                RuntimePolicy.Effects effects = policy.onClock(SystemClock.elapsedRealtime());
                if (effects.unloadModel) lastPolicyUnloadReason = "policy-idle";
                return effects;
            });
            main.postDelayed(this, 15_000L);
        }
    };


    private void onPolicyThread(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }

    private void dispatchPolicy(Supplier<RuntimePolicy.Effects> event) {
        onPolicyThread(() -> apply(event.get()));
    }

    private void runPolicy(Runnable action) {
        onPolicyThread(action);
    }

    private <T> T callOnPolicyThread(Supplier<T> supplier) {
        if (Looper.myLooper() == Looper.getMainLooper()) return supplier.get();
        java.util.concurrent.FutureTask<T> task = new java.util.concurrent.FutureTask<>(supplier::get);
        main.post(task);
        try {
            return task.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    ModelRuntime(Lfm3PocketApplication app) {
        this.app = app;
        this.historyTitle = defaultHistoryTitle();
        this.preferences = new RuntimePreferences(app);
        this.engine = new CountingEngine(app.inferenceEngine());
        this.playbackFocus = new AndroidPlaybackFocus(app);
        this.playback = new PlaybackController(app.newPlaybackTrackFactory(), playbackFocus::request,
                playbackFocus::abandon, audioWorker, main::post, (state, message) -> onPlayback(state, message));
        this.loopback = new LoopbackAgentApi(this::submitFromLoopback, message -> {}, preferences::loopbackToken);
        this.voiceActions = new VoiceActionRuntime(new ModelRuntimeVoiceCallbacks(this),
                new File(app.getCacheDir(), "voice-actions"), app::hasMicrophonePermission,
                audioWorker, main::post);
        this.tinyVocaPack = new TinyVocaPackStore(app);
        this.speechRouter = new SpeechReplyRouter(preferences, tinyVocaPack);
        this.systemSpeech = new AndroidSystemSpeechSynthesizer(app);
        dispatchPolicy(() -> policy.onSettings(preferences.keepLoaded(), preferences.idleMinutes(),
                preferences.lockPreview(), SystemClock.elapsedRealtime()));
        syncLoopback();
        listenForHeat();
        listenForBattery();
        main.postDelayed(idleTick, 15_000L);
    }

    private void syncLoopback() {
        loopback.setEnabled(preferences.loopbackEnabled());
    }

    /** Loopback can only submit text. It never reaches {@code approve}. */
    private boolean submitFromLoopback(String prompt) {
        if (prompt == null || prompt.isBlank()) return false;
        if (handle == 0L || isGenerating()) return false;
        main.post(() -> askFromNotification(prompt));
        return true;
    }

    public VoiceActionRuntime voice() { return voiceActions; }
    public void refreshVoiceAvailability() {
        dispatchPolicy(() -> policy.onVoiceTalkAvailable(voiceActions.isEnabled()));
    }
    @Override public long modelHandle() { return handle; }
    public long handle() { return handle; }
    @Override public InferenceEngine engine() { return engine; }
    @Override public PlaybackController playback() { return playback; }
    @Override public ExecutorService worker() { return worker; }
    @Override public Handler main() { return main; }
    @Override public Context app() { return app; }
    @Override public android.content.SharedPreferences modelPrefs() { return preferences.raw(); }
    @Override public ToolCoordinator toolCoordinator() { return app.toolCoordinator(); }
    @Override public java.util.Locale userLocale() {
        return app.getResources().getConfiguration().getLocales().get(0);
    }
    @Override public void policyListenStarted() {
        main.post(() -> {
            dispatchPolicy(() -> policy.onListenStarted("Listening…"));
            publishVoiceStatus("Listening…", false);
        });
    }
    @Override public void policyListenFinished() {
        main.post(() -> {
            dispatchPolicy(() -> policy.onListenFinished(SystemClock.elapsedRealtime()));
            publishVoiceStatus(null, true);
        });
    }
    @Override public void policyVoiceThinking() {
        dispatchPolicy(() -> policy.onGenerationStarted("Voice command", 512,
                SystemClock.elapsedRealtime()));
    }
    @Override public void policySpeechStarted() {
        dispatchPolicy(policy::onSpeechStarted);
    }
    @Override public void policyPlaybackStarted() {
        dispatchPolicy(policy::onPlaybackStarted);
    }
    @Override public void policySpeechFinished() {
        dispatchPolicy(() -> policy.onSpeechFinished(SystemClock.elapsedRealtime()));
    }
    @Override public void policyGenerationFinished() {
        dispatchPolicy(() -> policy.onGenerationFinished(SystemClock.elapsedRealtime()));
    }
    public String loadedPath() { return loadedPath == null ? "" : loadedPath; }
    public boolean keepLoaded() { return preferences.keepLoaded(); }
    public boolean acceptsTurn() { return callOnPolicyThread(() -> !policy.refuseNewTurns()); }
    public String conversationId() { return conversationId; }
    public String historyTitle() { return historyTitle; }
    public RuntimeNotice currentNotice() { return policyNotice(); }

    private String defaultHistoryTitle() {
        return app.getString(R.string.new_conversation_title);
    }

    public void setConversation(String id, String title) {
        if (id != null && !id.isBlank() && !id.equals(conversationId)) {
            conversationId = id;
            conversationEpoch.incrementAndGet();
        }
        if (title != null && !title.isBlank()) historyTitle = title;
        // commit: the next screen and instrumentation read this file immediately.
        preferences.raw().edit()
                .putString("history_conversation_id", conversationId)
                .putString("history_title", historyTitle)
                .commit();
    }

    public ChatSession session() {
        ensureSession();
        return session;
    }

    @Override public boolean isGenerating() { return session != null && session.isBusy(); }
    public boolean isLoading() { return loading; }
    public boolean isUnloading() { return unloading; }

    public boolean hasTranscript() {
        if (session == null) return false;
        if (session.isGenerating()) return true;
        ChatSnapshot snapshot = session.snapshot();
        return snapshot != null && !snapshot.turns.isEmpty();
    }

    public void noteSubmission(String text, int maxTokens) {
        pendingQuestion = text == null ? "" : text;
        pendingMax = maxTokens;
    }

    public void attach(Ui next) {
        ui = next;
        ensureSession();
        onAppForegrounded();
        if (next == null || !next.isUsable()) return;
        if (lastSnapshot != null) next.onSnapshot(lastSnapshot);
        if (!lastTranscript.isEmpty() || lastGenerating || !lastStatus.isEmpty())
            next.onChat(lastTranscript, lastStatus, lastGenerating);
        if (!lastAssistant.isEmpty()) next.onAssistantReply(lastAssistant);
        next.onVoiceStatus(null);
    }

    public void detach(Ui previous) {
        if (ui == previous) ui = null;
    }

    public void updateBackgroundSettings(boolean keep, int idleMinutes, boolean preview) {
        updateBackgroundSettings(keep, idleMinutes, preview, preferences.loopbackEnabled());
    }

    public void updateBackgroundSettings(boolean keep, int idleMinutes, boolean preview, boolean loopback) {
        preferences.setKeepLoaded(keep);
        preferences.setIdleMinutes(idleMinutes);
        preferences.setLockPreview(preview);
        preferences.setLoopbackEnabled(loopback);
        syncLoopback();
        dispatchPolicy(() -> policy.onSettings(preferences.keepLoaded(), preferences.idleMinutes(),
                preferences.lockPreview(), SystemClock.elapsedRealtime()));
    }

    public String loopbackToken() { return preferences.loopbackToken(); }
    public int loopbackPort() { return loopback.port(); }

    /**
     * Never overwrite a persisted model path or SAF {@code content://} URI with a transient
     * {@code /proc/self/fd/NN} descriptor path that becomes invalid after process restart.
     */
    static boolean shouldPersistModelPath(String path) {
        return path != null && !path.isEmpty() && !path.startsWith("/proc/self/fd/");
    }

    /**
     * Start loading the last selected model in the background as soon as the app opens so
     * weights are already mapped by the time the user enters chat.
     */
    public void preloadSavedModel() {
        if (handle != 0L || loading || unloading) return;
        String savedModel = preferences.raw().getString("model", "");
        String savedProjector = preferences.raw().getString("mmproj", "");
        if (savedModel == null || savedModel.isEmpty()) return;
        if (ModelImportCoordinator.isContentUri(savedModel)
                || ModelImportCoordinator.isContentUri(savedProjector)) {
            loading = true;
            String displayName = preferences.raw().getString("model_name", "GGUF");
            dispatchPolicy(() -> policy.onLoading(displayName));
            worker.execute(() -> {
                if (handle != 0L || unloading) {
                    main.post(() -> loading = false);
                    return;
                }
                ExternalModelStore store = app.externalModels();
                try {
                    String modelPath = ModelImportCoordinator.isContentUri(savedModel)
                            ? store.open(android.net.Uri.parse(savedModel)).path
                            : savedModel;
                    String projectorPath = savedProjector == null || savedProjector.isEmpty()
                            ? ""
                            : (ModelImportCoordinator.isContentUri(savedProjector)
                                    ? store.open(android.net.Uri.parse(savedProjector)).path
                                    : savedProjector);
                    boolean ok = loadBlocking(modelPath, projectorPath, null);
                    if (ok) {
                        java.util.HashSet<String> active = new java.util.HashSet<>();
                        if (ModelImportCoordinator.isContentUri(savedModel)) active.add(savedModel);
                        if (ModelImportCoordinator.isContentUri(savedProjector)) active.add(savedProjector);
                        store.releaseExcept(active);
                    }
                } catch (Throwable ignored) {
                    main.post(() -> {
                        loading = false;
                        dispatchPolicy(policy::onLoadFailed);
                        Ui target = ui;
                        if (target != null && target.isUsable()) {
                            target.onModelStateChanged(handle != 0L, null);
                        }
                    });
                }
            });
            return;
        }
        if (new File(savedModel).isFile()) {
            load(savedModel, savedProjector == null ? "" : savedProjector, null);
        }
    }

    public void load(String path, String projector, LoadCallback callback) {
        loading = true;
        dispatchPolicy(() -> policy.onLoading(path == null ? "" : new File(path).getName()));
        worker.execute(() -> loadBlocking(path, projector, callback));
    }

    /** Caller is the runtime worker. Safe for import, which already runs there. */
    public boolean loadBlocking(String path, String projector, LoadCallback callback) {
        loading = true;
        String fallbackName = path == null ? "" : new File(path).getName();
        String name = shouldPersistModelPath(path)
                ? fallbackName
                : preferences.raw().getString("model_name", fallbackName);
        long started = SystemClock.elapsedRealtime();
        long next = 0L;
        String error = null;
        try {
            GgufSelection.validate(new File(path),
                    projector == null || projector.isEmpty() ? null : new File(projector));
            if (engine instanceof NativeInferenceEngine
                    && !NativeInferenceEngine.supportsPhoneNativeKernels()) {
                error = NativeInferenceEngine.unsupportedCpuMessage();
            } else {
                next = engine.loadModel(path, projector == null || projector.isEmpty() ? null : projector);
                if (next == 0L) error = "Native engine could not load model (check RAM and GGUF compatibility)";
            }
        } catch (Throwable thrown) {
            error = thrown.toString();
        }
        long loadMs = SystemClock.elapsedRealtime() - started;
        String failure = error;
        app.diagnostics().record(LocalDiagnostics.Kind.MODEL_LOAD, loadMs,
                failure == null ? "ok path=" + name : "error=" + failure);
        try {
            app.diagnostics().writeTo(new File(app.getFilesDir(), "diagnostics.ndjson"));
        } catch (Exception ignored) {
        }
        long loaded = next;
        if (loaded != 0L) {
            long previous = handle;
            if (previous != 0L && previous != loaded) engine.unloadModel(previous);
            handle = loaded;
            loadedPath = path;
            if (shouldPersistModelPath(path)) {
                preferences.raw().edit().putString("model", path).apply();
            }
        }
        String status = failure == null
                ? (projector == null || projector.isEmpty()
                    ? "Text model ready · offline"
                    : "Model and projector loaded • offline")
                : "Model error: " + failure;
        main.post(() -> {
            loading = false;
            if (loaded != 0L) {
                lastKvTrimReleaseMs = 0L;
                lastPolicyUnloadReason = "";
                dispatchPolicy(() -> policy.onLoaded(name, SystemClock.elapsedRealtime()));
                dispatchPolicy(() -> policy.onVoiceTalkAvailable(voiceActions.isEnabled()));
            } else {
                dispatchPolicy(policy::onLoadFailed);
            }
            if (callback != null) callback.finished(status);
            Ui target = ui;
            if (target != null && target.isUsable()) {
                target.onModelStateChanged(loaded != 0L, callback == null ? status : null);
            }
        });
        return loaded != 0L;
    }

    public void unload(String cause, Runnable done) {
        // Contract: public unload must stop system speech (see ModelRuntimeSpeechStopContractTest).
        systemSpeech.stop();
        onPolicyThread(() -> unloadOnPolicyThread(cause, done));
    }

    private void unloadOnPolicyThread(String cause, Runnable done) {
        if (unloading) {
            if (done != null) done.run();
            return;
        }
        unloading = true;
        speechCancel = true;
        // Voice capture/confirmation owns a separate recorder and can outlive a chat turn.
        // Stop it before retiring the model handle so no late WAV callback can infer on it.
        voiceActions.cancelAll("Model unloading");
        if (session != null) session.stop();
        engine.stop();
        playback.stop();
        systemSpeech.stop();
        last = policy.unloadRequested();
        String why = cause == null ? "user" : cause;
        if (done == null) {
            Ui startTarget = ui;
            if (startTarget != null && startTarget.isUsable()) {
                startTarget.onModelStateChanged(false, "Unloading model…");
            }
        }
        worker.execute(() -> {
            long current = handle;
            if (current != 0L) {
                engine.unloadModel(current);
                if (handle == current) handle = 0L;
            }
            loadedPath = "";
            app.diagnostics().record(LocalDiagnostics.Kind.MODEL_LOAD, 0L, "unload=" + why);
            onPolicyThread(() -> {
                unloading = false;
                if (handle == 0L) {
                    try { app.externalModels().releaseAll(); } catch (Exception ignored) { }
                }
                publish(last);
                if (done != null) {
                    done.run();
                } else {
                    String statusMsg = unloadStatusFor(why);
                    lastStatus = statusMsg;
                    Ui finishTarget = ui;
                    if (finishTarget != null && finishTarget.isUsable()) {
                        finishTarget.onModelStateChanged(handle != 0L, statusMsg);
                    }
                }
            });
        });
    }

    private static String unloadStatusFor(String why) {
        if (why != null && why.startsWith("policy-thermal")) {
            return "Model unloaded due to severe device heat. Reload it in Settings once cooled.";
        }
        if (why != null && why.startsWith("policy-memory")) {
            return "Model unloaded due to critical memory pressure. Reload it in Settings to continue.";
        }
        if (why != null && why.startsWith("policy-idle")) {
            return "Model unloaded after idle timeout. Reload it in Settings to continue.";
        }
        if ("policy-cancel".equals(why) || "policy".equals(why)) {
            return "Model unloaded due to memory or thermal pressure. Reload it in Settings to continue.";
        }
        if ("fgs-timeout".equals(why)) {
            return "Background session timed out; model unloaded. Reload it in Settings to continue.";
        }
        return "Model unloaded. Selected files remain available for reload.";
    }

    @Override public void stopTurn() {
        voiceActions.cancelAll("Stopped");
        speechCancel = true;
        if (session != null) session.stop();
        engine.stop();
        playback.stop();
        systemSpeech.stop();
        dispatchPolicy(() -> policy.stopRequested(SystemClock.elapsedRealtime()));
    }

    public void stopSpeech() {
        speechCancel = true;
        playback.stop();
        systemSpeech.stop();
        engine.stop();
        dispatchPolicy(() -> policy.onSpeechFinished(SystemClock.elapsedRealtime()));
        Ui target = ui;
        if (target != null && target.isUsable()) {
            target.onSpeech(false, "Spoken response cancelled.");
        }
    }

    public void clearConversation() {
        conversationEpoch.incrementAndGet();
        voiceActions.cancelAll("Cleared");
        speechCancel = true;
        playback.stop();
        systemSpeech.stop();
        if (session != null) session.clear();
        lastAssistant = "";
        final long current = handle;
        if (current != 0L) {
            worker.execute(() -> {
                if (handle == current) engine.releaseSession(current);
            });
        }
        if (session != null) {
            lastSnapshot = session.snapshot();
            lastTranscript = FlatTranscript.format(lastSnapshot);
            lastStatus = lastSnapshot == null ? "" : lastSnapshot.status;
            lastGenerating = session.isBusy();
        } else {
            lastSnapshot = new ChatSnapshot(java.util.Collections.emptyList(), "Conversation cleared.", false);
            lastTranscript = FlatTranscriptFormatter.EMPTY_TRANSCRIPT;
            lastStatus = "Conversation cleared.";
            lastGenerating = false;
            Ui target = ui;
            if (target != null && target.isUsable()) {
                target.onSnapshot(lastSnapshot);
                target.onChat(lastTranscript, lastStatus, false);
            }
        }
    }

    /** Explicit, user-visible summarize-and-compact step for the active conversation. */
    public boolean requestCompact() {
        ensureSession();
        if (handle == 0L || session == null || isGenerating()) return false;
        int tokens = Math.max(128, Math.min(512, pendingMax <= 0 ? 256 : pendingMax));
        return session.requestCompact(tokens);
    }

    /** Retry the most recent interrupted assistant turn in the live session. */
    public boolean retryInterrupted(String turnId) {
        ensureSession();
        if (handle == 0L || session == null) return false;
        int tokens = pendingMax <= 0 ? 512 : pendingMax;
        noteSubmission("retry", tokens);
        return session.retryInterrupted(turnId, tokens);
    }

    /** Pure staleness check for async Room restores against conversation switches and clears. */
    static boolean shouldApplyRestore(String requestedId, String activeId,
                                      int requestedConversationEpoch, int activeConversationEpoch,
                                      int requestedSessionEpoch, int activeSessionEpoch) {
        if (requestedId == null || requestedId.isBlank()) return false;
        if (requestedSessionEpoch != activeSessionEpoch) return false;
        if (requestedConversationEpoch != activeConversationEpoch) return false;
        return requestedId.equals(activeId);
    }

    /**
     * Force a Room rehydrate after conversation id changes on a cold process.
     * Room work runs on the runtime worker; the live session is updated on the main thread.
     */
    public void restoreOpenConversation() {
        ensureSession();
        final String id = conversationId;
        final int born = sessionEpoch;
        final int restoreEpoch = conversationEpoch.get();
        if (id == null || id.isBlank()) return;
        worker.execute(() -> {
            if (!shouldApplyRestore(id, conversationId, restoreEpoch, conversationEpoch.get(), born, sessionEpoch)) {
                return;
            }
            if (history == null) history = new HistoryRepository(HistoryDatabase.get(app));
            java.util.List<HistoryRepository.StoredMessage> rows;
            try {
                rows = history.messages(id);
            } catch (Exception ignored) {
                return;
            }
            if (rows.isEmpty()) return;
            java.util.ArrayList<ChatSession.RestoredMessage> restored = new java.util.ArrayList<>();
            for (HistoryRepository.StoredMessage row : rows) {
                java.util.List<ChatSnapshot.ToolCall> restoredCalls = new java.util.ArrayList<>();
                for (HistoryRepository.ToolExecution ex : row.getToolOutcomes()) {
                    String wire = ex.getTool();
                    String argsJson = ex.getArgumentsJson();
                    restoredCalls.add(new ChatSnapshot.ToolCall(
                            ex.getRequestId(),
                            wire,
                            ToolCoordinator.titleForWireName(wire),
                            ToolCoordinator.compactSummaryFromJson(wire, argsJson),
                            argsJson,
                            ex.getStatus(),
                            ToolCoordinator.stripUntrustedPrefix(ex.getResultText()),
                            false,
                            false,
                            ex.getSucceeded(),
                            0L));
                }
                restored.add(new ChatSession.RestoredMessage(
                        row.getRole(), row.getText(), row.getTurnId(), row.getTurnStatus(),
                        row.getMediaRef(), row.getReasoning(), restoredCalls));
            }
            HistoryRepository.ConversationSummary summaryState = null;
            try {
                summaryState = history.conversationSummary(id);
            } catch (Exception ignored) {
            }
            final String summary = summaryState == null ? "" : summaryState.getSummary();
            final int summarizedCount = summaryState == null ? 0 : (int) Math.min(Integer.MAX_VALUE, summaryState.getSummarizedMessageCount());
            main.post(() -> {
                if (session == null || !shouldApplyRestore(
                        id, conversationId, restoreEpoch, conversationEpoch.get(), born, sessionEpoch)) {
                    return;
                }
                if (session.isGenerating()) return;
                ChatSnapshot existing = session.snapshot();
                if (existing != null && !existing.turns.isEmpty()) return;
                session.restore(restored, summary, summarizedCount);
                String restoredReply = "";
                for (int i = restored.size() - 1; i >= 0; i--) {
                    ChatSession.RestoredMessage item = restored.get(i);
                    if ("assistant".equalsIgnoreCase(item.role)) {
                        String cleaned = ThinkingSanitizer.clean(item.text).answer;
                        if (!cleaned.isBlank()) {
                            restoredReply = cleaned;
                            break;
                        }
                    }
                }
                lastAssistant = restoredReply;
                lastSnapshot = session.snapshot();
                lastTranscript = FlatTranscript.format(lastSnapshot);
                lastStatus = lastSnapshot == null ? "" : lastSnapshot.status;
                lastGenerating = session.isGenerating();
                Ui target = ui;
                if (target != null && target.isUsable()) {
                    if (lastSnapshot != null) target.onSnapshot(lastSnapshot);
                    target.onChat(lastTranscript, lastStatus, lastGenerating);
                    if (!lastAssistant.isEmpty()) target.onAssistantReply(lastAssistant);
                }
            });
        });
    }

    public void speak(String replyText) {
        if (playbackActive() || systemSpeech.isSpeaking()) {
            speechCancel = true;
            playback.stop();
            systemSpeech.stop();
            engine.stop();
            dispatchPolicy(() -> policy.onSpeechFinished(SystemClock.elapsedRealtime()));
            if (ui != null && ui.isUsable()) ui.onSpeech(false, "Spoken response cancelled.");
            return;
        }
        if (policyNotice().kind == RuntimeNotice.Kind.SPEAKING && !playbackNoted) {
            speechCancel = true;
            engine.stop();
            systemSpeech.stop();
            if (ui != null && ui.isUsable()) ui.onSpeech(true, "Stopping spoken response...");
            return;
        }
        boolean lightweightTts = preferences.tinyVocaEnglishTtsEnabled();
        if (handle == 0L && !lightweightTts) {
            if (ui != null && ui.isUsable()) ui.onSpeech(false, "Load a model to speak replies.");
            return;
        }
        String reply = SpeechPrompt.spokenText(replyText == null ? "" : replyText);
        if (reply.isBlank()) {
            if (ui != null && ui.isUsable()) ui.onSpeech(false, "Nothing speakable in the last reply.");
            return;
        }
        speechCancel = false;
        dispatchPolicy(policy::onSpeechStarted);
        final String spoken = reply;
        worker.execute(() -> {
            if (speechCancel) {
                main.post(() -> {
                    dispatchPolicy(() -> policy.onSpeechFinished(SystemClock.elapsedRealtime()));
                    if (ui != null && ui.isUsable()) ui.onSpeech(false, "Spoken response cancelled.");
                });
                return;
            }
            SpeechReplyBackend backend = speechRouter.select(spoken);
            if (backend == SpeechReplyBackend.SYSTEM_TTS) {
                generateSystemSpeech(spoken, localeForSpokenText(spoken));
                return;
            }
            if (backend == SpeechReplyBackend.TINY_VOCA) {
                main.post(() -> {
                    if (ui != null && ui.isUsable()) ui.onSpeech(true, "Speaking with English tiny-voca…");
                });
                generateTinyVocaSpeech(spoken);
                return;
            }
            if (handle == 0L) {
                if (preferences.tinyVocaEnglishTtsEnabled()) {
                    generateSystemSpeech(spoken, localeForSpokenText(spoken));
                } else {
                    main.post(() -> {
                        dispatchPolicy(() -> policy.onSpeechFinished(SystemClock.elapsedRealtime()));
                        if (ui != null && ui.isUsable()) ui.onSpeech(false, "Load a model to speak replies.");
                    });
                }
                return;
            }
            main.post(() -> {
                if (ui != null && ui.isUsable()) ui.onSpeech(true, "Generating spoken response locally…");
            });
            int frames = SpeechOptions.framesForText(spoken);
            generateSpeech(spoken, frames);
        });
    }

    @Override public void showConsent(String id, String text, boolean safe) {
        dispatchPolicy(() -> policy.onConsent(id, text, safe));
    }

    @Override public void clearConsent() {
        dispatchPolicy(() -> policy.onConsentCleared(SystemClock.elapsedRealtime()));
    }

    public void onAppForegrounded() {
        onPolicyThread(() -> {
            if (!unloading && handle != 0L && service == null && last != null && last.wantService) {
                publish(last);
            }
        });
    }

    public void onAppBackgrounded() {
        if (!preferences.keepLoaded()) unload("left", null);
    }

    public void onChatFinished() {
        if (!preferences.keepLoaded()) unload("chat-closed", null);
    }

    /**
     * Only shed the KV/hybrid session on actual memory pressure — never on routine
     * {@code TRIM_MEMORY_UI_HIDDEN} (20), {@code TRIM_MEMORY_BACKGROUND} (40), or
     * {@code TRIM_MEMORY_RUNNING_LOW} (10) fired whenever the user switches apps or locks the screen.
     */
    static boolean shouldReleaseKvForTrim(int level) {
        return level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                || level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE;
    }

    /**
     * Decides whether a memory trim should escalate to unloading the model weights.
     * When {@code keepLoaded} is enabled, prefer releasing the KV session first (Stage 1)
     * and only unload weights if the kernel reports {@code lowMemory}, a second critical trim
     * arrives within 60 seconds, or the process has no active foreground service / low headroom.
     */
    static boolean shouldEscalateTrimToUnload(int level, boolean keepLoaded, boolean fgsActive,
                                              boolean systemLowMemory, long availRamMb,
                                              long nowMs, long lastKvReleaseMs) {
        boolean complete = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE;
        boolean critical = level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL;
        if (!complete && !critical) return false;
        if (!keepLoaded) return true;
        if (systemLowMemory) return true;
        if (complete) {
            return !(fgsActive && availRamMb >= 512L);
        }
        // TRIM_MEMORY_RUNNING_CRITICAL while keepLoaded is on: shed KV first; escalate only if
        // pressure repeats after a short debounce window (OEMs like ColorOS/Realme often fire
        // duplicate trim=15 bursts within <1s even with >1 GB free RAM).
        long sinceKvRelease = nowMs - lastKvReleaseMs;
        if (lastKvReleaseMs > 0L && sinceKvRelease >= 0L && sinceKvRelease < 3_000L && availRamMb >= 512L) {
            return false;
        }
        return lastKvReleaseMs > 0L && sinceKvRelease <= 60_000L;
    }

    public void onTrimMemory(int level) {
        main.post(() -> {
            boolean complete = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE;
            boolean critical = level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL;
            long now = SystemClock.elapsedRealtime();
            boolean releaseKv = shouldReleaseKvForTrim(level);
            long previousKvReleaseMs = lastKvTrimReleaseMs;
            // Drop the text KV/hybrid session under real memory pressure (ADR 0001),
            // without wiping KV on routine UI_HIDDEN / BACKGROUND app switches.
            long current = handle;
            if (current != 0L && releaseKv) {
                lastKvTrimReleaseMs = now;
                worker.execute(() -> {
                    if (handle == current) engine.releaseSession(current);
                });
            }
            if (!complete && !critical) {
                app.diagnostics().record(LocalDiagnostics.Kind.MEMORY, 0L,
                        "trim=" + level + (releaseKv ? ",kv-released" : ""));
                return;
            }
            DeviceResourceMonitor.Snapshot mem = DeviceResourceMonitor.sample(app);
            boolean keep = preferences.keepLoaded();
            boolean fgsActive = service != null;
            if (!shouldEscalateTrimToUnload(level, keep, fgsActive, mem.lowMemory, mem.availRamMb,
                    now, previousKvReleaseMs)) {
                app.diagnostics().record(LocalDiagnostics.Kind.MEMORY, 0L,
                        "trim=" + level + ",kv-released-keep-weights,availMb=" + mem.availRamMb
                                + ",fgs=" + fgsActive);
                return;
            }
            lastPolicyUnloadReason = "policy-memory";
            dispatchPolicy(() -> policy.onTrim(complete, now));
        });
    }

    public void onServiceTimeout() {
        unload("fgs-timeout", null);
    }

    public void serviceReady(ModelRuntimeService started) {
        service = started;
        serviceStartRequested = false;
        publish(last);
    }

    public void serviceGone(ModelRuntimeService stopped) {
        if (service == stopped) {
            service = null;
            serviceStartRequested = false;
        }
    }

    public void serviceStartRefused(ModelRuntimeService failed) {
        if (service == failed) service = null;
        serviceStartRequested = false;
    }

    public void onServiceAction(String action, String proposalId) {
        onServiceAction(action, proposalId, null);
    }

    public void onServiceAction(String action, String proposalId, String askText) {
        if (ModelRuntimeService.ACTION_STOP.equals(action)) stopTurn();
        else if (ModelRuntimeService.ACTION_UNLOAD.equals(action)) unload("notification", null);
        else if (ModelRuntimeService.ACTION_APPROVE.equals(action)) resolveConsent(proposalId, true);
        else if (ModelRuntimeService.ACTION_REJECT.equals(action)) resolveConsent(proposalId, false);
        else if (ModelRuntimeService.ACTION_ASK.equals(action)) askFromNotification(askText);
        else if (ModelRuntimeService.ACTION_TALK.equals(action)) voiceActions.beginShadeTalk();
    }

    /** Updates the live session's expandable tool call card after user approval/rejection. */
    public void recordToolOutcomeInSession(ToolCoordinator.Proposal proposal, ToolRegistry.Request request,
                                           ToolCoordinator.Outcome outcome) {
        if (session == null || request == null || outcome == null || outcome.result == null) return;
        String id = proposal == null ? java.util.UUID.randomUUID().toString() : proposal.id;
        String wire = ToolCoordinator.wireName(request);
        String argsJson = McpClient.toCompactJson(request.arguments);
        session.recordCompletedToolCall(new ChatSnapshot.ToolCall(
                id,
                wire,
                ToolCoordinator.title(request),
                ToolCoordinator.compactSummary(request),
                argsJson,
                outcome.result.status,
                ToolCoordinator.stripUntrustedPrefix(outcome.result.message),
                false,
                false,
                outcome.result.succeeded,
                0L));
    }

    /** RemoteInput Ask inserts a user message. It never authorizes a tool. */
    public void askFromNotification(String text) {
        if (text == null || text.isBlank()) return;
        if (handle == 0L) {
            dispatchPolicy(() -> policy.onSettings(preferences.keepLoaded(), preferences.idleMinutes(),
                    preferences.lockPreview(), SystemClock.elapsedRealtime()));
            return;
        }
        if (isGenerating()) return;
        ensureSession();
        int maxTokens = pendingMax > 0 ? pendingMax : InferenceSettings.DEFAULT.maxTokens;
        boolean searchEnabled = SearchEndpointPolicy.enabled(
                preferences.raw().getBoolean("search_enabled", false),
                preferences.raw().getString("search_endpoint", ""));
        String endpoint = searchEnabled ? preferences.raw().getString("search_endpoint", "") : "";
        noteSubmission(text.trim(), maxTokens);
        if (!session.submit(text.trim(), java.util.Collections.emptyList(), searchEnabled, endpoint, maxTokens)) {
            // Still answering or handle raced to zero.
        }
    }

    private void proposeToolsFromAssistant(String assistantText) {
        worker.execute(() -> {
            ToolCoordinator.Outcome outcome = ModelToolBridge.proposeFromAssistant(
                    new ToolRegistry(), app.toolCoordinator(), assistantText);
            if (outcome == null) return;
            main.post(() -> {
                if ("PENDING_CONFIRMATION".equals(outcome.status) && outcome.proposal != null) {
                    showConsent(outcome.proposal.id, ToolCoordinator.describe(outcome.proposal.request),
                            outcome.proposal.risk == ToolCoordinator.Risk.SAFE);
                }
                if (ui != null && ui.isUsable()) {
                    ToolRegistry.Request request = outcome.proposal == null ? null : outcome.proposal.request;
                    ui.onToolOutcome(request, outcome);
                }
            });
        });
    }

    /**
     * Instrumentation resets the fake engine between tests. Drops the live session
     * without a new process. A reset while chat is on screen only clears engine
     * counters: unloading here would make the open activity's next send a no-op.
     */
    public void discardForTest() {
        if (ui != null && ui.isUsable()) return;
        sessionEpoch++;
        conversationEpoch.incrementAndGet();
        ChatSession old = session;
        session = null;
        speechCancel = true;
        long current = handle;
        handle = 0L;
        loadedPath = "";
        lastAssistant = "";
        lastTranscript = FlatTranscriptFormatter.EMPTY_TRANSCRIPT;
        lastStatus = "";
        lastGenerating = false;
        lastSnapshot = null;
        conversationId = "";
        historyTitle = defaultHistoryTitle();
        if (old != null) old.close();
        if (current != 0L) engine.unloadModel(current);
        playback.stop();
        systemSpeech.stop();
        runPolicy(policy::reset);
        dispatchPolicy(() -> policy.onVoiceTalkAvailable(false));
        dispatchPolicy(() -> policy.onSettings(preferences.keepLoaded(), preferences.idleMinutes(),
                preferences.lockPreview(), SystemClock.elapsedRealtime()));
        if (service != null) service.stopNow();
        releaseWake();
    }

    @Override public void onVoiceStatus(String status) {
        main.post(() -> {
            if (unloading) return;
            lastStatus = status == null ? "" : status;
            Ui target = ui;
            if (target != null && target.isUsable()) {
                String transcript = lastTranscript;
                if ((transcript == null || transcript.isEmpty()) && !lastGenerating) {
                    transcript = FlatTranscriptFormatter.EMPTY_TRANSCRIPT;
                }
                target.onChat(transcript, lastStatus, lastGenerating);
            }
            publishVoiceStatus(status, true);
        });
    }

    private void publishVoiceStatus(String status, boolean allowStatusText) {
        Ui attached = ui;
        if (attached == null || !attached.isUsable()) return;
        boolean listening = voiceActions != null && voiceActions.isListening();
        boolean blocked = (isGenerating() || currentNotice().kind == RuntimeNotice.Kind.APPROVAL) && !listening;
        if (!allowStatusText || blocked) {
            attached.onVoiceStatus(null);
            return;
        }
        attached.onVoiceStatus(status == null ? "" : status);
    }

    @Override public void resolveConsent(String proposalId, boolean approve) {
        if (proposalId == null || proposalId.isEmpty()) return;
        worker.execute(() -> {
            ToolCoordinator coordinator = app.toolCoordinator();
            ToolCoordinator.Outcome outcome = approve
                    ? coordinator.approve(proposalId) : coordinator.reject(proposalId);
            main.post(() -> {
                clearConsent();
                if (outcome != null && ui != null && ui.isUsable()) {
                    ToolRegistry.Request request = outcome.proposal == null ? null : outcome.proposal.request;
                    ui.onToolOutcome(request, outcome);
                }
                if (outcome != null && "PENDING_CONFIRMATION".equals(outcome.status) && outcome.proposal != null) {
                    showConsent(outcome.proposal.id, ToolCoordinator.describe(outcome.proposal.request),
                            outcome.proposal.risk == ToolCoordinator.Risk.SAFE);
                }
            });
        });
    }

    private static java.util.Locale localeForSpokenText(String text) {
        if (text != null && text.matches("(?s).*[ąćęłńóśźżĄĆĘŁŃÓŚŹŻ].*")) {
            return new java.util.Locale("pl");
        }
        return java.util.Locale.US;
    }

    private void generateSystemSpeech(String reply, java.util.Locale locale) {
        if (speechCancel) {
            main.post(() -> {
                dispatchPolicy(() -> policy.onSpeechFinished(SystemClock.elapsedRealtime()));
                if (ui != null && ui.isUsable()) ui.onSpeech(false, "Spoken response cancelled.");
            });
            return;
        }
        main.post(() -> {
            if (ui != null && ui.isUsable()) ui.onSpeech(true, "Speaking with system voice…");
        });
        systemSpeech.speak(locale == null ? java.util.Locale.US : locale, reply, new AndroidSystemSpeechSynthesizer.Callback() {
            @Override public void onDone() {
                main.post(() -> {
                    dispatchPolicy(() -> policy.onSpeechFinished(SystemClock.elapsedRealtime()));
                    if (ui != null && ui.isUsable()) ui.onSpeech(false, "Spoken response finished.");
                });
            }
            @Override public void onError(String message) {
                main.post(() -> {
                    dispatchPolicy(() -> policy.onSpeechFinished(SystemClock.elapsedRealtime()));
                    if (ui != null && ui.isUsable()) ui.onSpeech(false, message);
                });
            }
        });
    }

    private void generateTinyVocaSpeech(String reply) {
        String failure = null;
        byte[] audio = new byte[0];
        int sampleRate = 24000;
        boolean synthesisCancelled = false;
        try (TinyVocaOnnxRuntime synth = TinyVocaOnnxRuntime.open(app)) {
            sampleRate = synth.sampleRateHz();
            audio = synth.synthesizePcm16(reply, "F2", () -> speechCancel);
        } catch (SpeechSynthesisCancelled cancelled) {
            synthesisCancelled = true;
        } catch (Throwable thrown) {
            failure = thrown.getMessage() == null ? thrown.toString() : thrown.getMessage();
        }
        boolean cancelled = speechCancel || synthesisCancelled;
        byte[] pcm = audio;
        int rate = sampleRate;
        String error = failure;
        if (!cancelled && (error != null || pcm.length == 0)) {
            app.diagnostics().record(LocalDiagnostics.Kind.STRESS, 0L,
                    "tiny-voca failed: " + (error == null ? "empty audio" : error));
        }
        main.post(() -> {
            if (cancelled) {
                dispatchPolicy(() -> policy.onSpeechFinished(SystemClock.elapsedRealtime()));
                if (ui != null && ui.isUsable()) ui.onSpeech(false, "Spoken response cancelled.");
                return;
            }
            if (error != null || pcm.length == 0) {
                if (ui != null && ui.isUsable()) {
                    ui.onSpeech(true, error == null
                            ? "tiny-voca produced no audio; using system voice…"
                            : "tiny-voca failed (" + error + "); using system voice…");
                }
                worker.execute(() -> generateSystemSpeech(reply, localeForSpokenText(reply)));
                return;
            }
            if (!playback.play(pcm, rate, 1, 16)) {
                dispatchPolicy(() -> policy.onSpeechFinished(SystemClock.elapsedRealtime()));
                if (ui != null && ui.isUsable()) ui.onSpeech(false, "Could not start spoken playback.");
                return;
            }
            if (ui != null && ui.isUsable()) ui.onSpeech(false, "Playing tiny-voca speech…");
        });
    }

    private void generateSpeech(String reply, int frames) {
        java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
        int[] format = new int[] { 24000, 1, 16 };
        long[] lastUi = { 0L };
        String error;
        try {
            engine.resetStop();
            if (speechCancel) engine.stop();
            error = engine.generateSpeech(handle, SpeechPrompt.tts(reply, "en", "female"),
                    new SpeechOptions("en", "female", frames), new InferenceEngine.AudioSink() {
                        @Override public void onAudioFormat(int sampleRateHz, int channelCount, int bitsPerSample) {
                            format[0] = sampleRateHz;
                            format[1] = channelCount;
                            format[2] = bitsPerSample;
                        }
                        @Override public void onAudioPcm(byte[] interleavedLittleEndianPcm) {
                            try { pcm.write(interleavedLittleEndianPcm); }
                            catch (java.io.IOException e) { throw new IllegalStateException(e); }
                            long now = SystemClock.uptimeMillis();
                            if (now - lastUi[0] < 250L) return;
                            lastUi[0] = now;
                            int bytes = pcm.size();
                            float seconds = bytes / 2f / Math.max(8000, format[0]);
                            main.post(() -> {
                                if (speechCancel) return;
                                if (ui != null && ui.isUsable()) ui.onSpeech(true, String.format(java.util.Locale.US,
                                        "Generating spoken response… %.1fs audio so far", seconds));
                            });
                        }
                    });
        } catch (Throwable thrown) {
            error = thrown.toString();
        }
        String failure = error;
        byte[] audio = pcm.toByteArray();
        boolean cancelled = speechCancel;
        main.post(() -> {
            if (cancelled) {
                dispatchPolicy(() -> policy.onSpeechFinished(SystemClock.elapsedRealtime()));
                if (ui != null && ui.isUsable()) ui.onSpeech(false, "Spoken response cancelled.");
                return;
            }
            if (failure != null || audio.length == 0) {
                if (preferences.tinyVocaEnglishTtsEnabled()) {
                    if (ui != null && ui.isUsable()) {
                        ui.onSpeech(true, "Omni speech unavailable; using system voice…");
                    }
                    worker.execute(() -> generateSystemSpeech(reply, localeForSpokenText(reply)));
                    return;
                }
                dispatchPolicy(() -> policy.onSpeechFinished(SystemClock.elapsedRealtime()));
                if (ui != null && ui.isUsable()) ui.onSpeech(false, failure != null
                        ? "Spoken response failed: " + failure : "Spoken response produced no audio.");
                return;
            }
            if (!playback.play(audio, format[0], format[1], format[2])) {
                dispatchPolicy(() -> policy.onSpeechFinished(SystemClock.elapsedRealtime()));
                if (ui != null && ui.isUsable()) ui.onSpeech(false, "Could not start spoken playback.");
                return;
            }
            if (ui != null && ui.isUsable()) ui.onSpeech(false, "Starting spoken response...");
        });
    }

    private void onPlayback(PlaybackController.State state, String message) {
        if (state == PlaybackController.State.STARTING || state == PlaybackController.State.PLAYING) {
            if (!playbackNoted) {
                playbackNoted = true;
                dispatchPolicy(policy::onPlaybackStarted);
            }
        } else if (state == PlaybackController.State.IDLE || state == PlaybackController.State.ERROR
                || state == PlaybackController.State.CLOSED) {
            if (playbackNoted) {
                playbackNoted = false;
                dispatchPolicy(() -> policy.onSpeechFinished(SystemClock.elapsedRealtime()));
            }
            voiceActions.onPlaybackIdle();
        }
        if (ui != null && ui.isUsable()) ui.onPlayback(message);
    }

    private void ensureSession() {
        if (session != null) return;
        if (history == null) history = new HistoryRepository(HistoryDatabase.get(app));
        // Queue one process-start recovery before ChatSession can enqueue a new turn.
        // The single-thread runtime worker preserves this ordering and prevents a
        // later Activity resume from globally interrupting an active conversation.
        if (historyRecoveryScheduled.compareAndSet(false, true)) {
            worker.execute(() -> {
                try {
                    history.markInterruptedGenerations(System.currentTimeMillis());
                } catch (Exception error) {
                    app.diagnostics().record(LocalDiagnostics.Kind.STRESS, 0L,
                            "startup recovery failed: " + error.getClass().getSimpleName());
                }
            });
        }
        final int born = sessionEpoch;
        ChatSession.Persistence persistence = new ChatSession.Persistence() {
            @Override public void begin(ChatSession.StartedTurn turn) throws Exception {
                beginTurn(turn);
            }
            private final java.util.concurrent.atomic.AtomicReference<Runnable> pendingPartialSave =
                    new java.util.concurrent.atomic.AtomicReference<>();
            @Override public void updatePartial(String turnId, String assistantText, long atMs) {
                updatePartial(turnId, assistantText, null, atMs);
            }
            @Override public void updatePartial(String turnId, String assistantText, String reasoning, long atMs) {
                final String text = assistantText == null ? "" : assistantText;
                final String reason = reasoning == null || reasoning.isEmpty() ? null : reasoning;
                final Runnable task = () -> {
                    try {
                        HistoryRepository repo = history;
                        if (repo == null) {
                            repo = new HistoryRepository(HistoryDatabase.get(app));
                            history = repo;
                        }
                        repo.updateTurnPartial(turnId, text, reason, atMs);
                    } catch (Exception ignored) {
                        // Best-effort partial write; terminal save persists final state.
                    }
                };
                boolean first = pendingPartialSave.getAndSet(task) == null;
                if (first) {
                    partialSaveWorker.execute(() -> {
                        Runnable latest = pendingPartialSave.getAndSet(null);
                        if (latest != null) latest.run();
                    });
                }
            }
            @Override public void saveCompactSummary(String summary, int summarizedMessageCount, long atMs) throws Exception {
                if (history == null) history = new HistoryRepository(HistoryDatabase.get(app));
                String id = conversationId == null ? "" : conversationId;
                if (id.isBlank()) return;
                String title = historyTitle == null || historyTitle.isBlank() ? defaultHistoryTitle() : historyTitle;
                history.updateConversationSummary(id, title, summary, summarizedMessageCount, atMs);
            }
            @Override public void save(ChatSession.CompletedTurn turn) throws Exception {
                saveTurn(turn);
            }
        };
        session = new ChatSession(engine, worker, (task, delay) -> main.postDelayed(task, delay),
                this::handle, WebSearch::search, persistence,
                path -> HistoryMediaCleanup.deleteAttachment(app, path),
                (text, status, running) -> {
                    if (born != sessionEpoch) return;
                    onChat(text, status, running);
                }, snapshot -> {
                    if (born != sessionEpoch) return;
                    onSnapshot(snapshot);
                });
        session.setToolHost(createChatToolHost());
        session.setTelemetry(app.processingTelemetry(), () -> DeviceResourceMonitor.sample(app).compactBadge());
        session.setReasoningSupplier(() -> activeSettings.reasoningLevel);
    }

    private ChatSession.ToolHost createChatToolHost() {
        return new ChatSession.ToolHost() {
            @Override public LfmToolCatalog.Capabilities capabilities(boolean useSearch, String searchEndpoint) {
                boolean webReady = useSearch && SearchEndpointPolicy.enabled(true, searchEndpoint);
                boolean weatherReady = preferences.networkToolsEnabled();
                boolean calRead = app.checkSelfPermission(android.Manifest.permission.READ_CALENDAR)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
                String pickedId = preferences.calendarWriteId();
                boolean calWrite = calRead
                        && app.checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR)
                                == android.content.pm.PackageManager.PERMISSION_GRANTED
                        && pickedId != null && !pickedId.trim().isEmpty();
                boolean notifReady = preferences.notificationAccessEnabled()
                        && !preferences.notificationAllowlist().isEmpty();
                String wsUri = preferences.workspaceTreeUri();
                boolean wsReady = wsUri != null && !wsUri.trim().isEmpty();
                java.util.List<LfmToolCatalog.ToolSpec> extraTools = new java.util.ArrayList<>();
                for (ExternalToolProvider provider : app.externalToolProviders()) {
                    extraTools.addAll(provider.discoverTools());
                }
                return new LfmToolCatalog.Capabilities(
                        webReady, weatherReady, calRead, calWrite, preferences.calendarWriteLabel(), notifReady,
                        wsReady, preferences.workspaceLabel(), extraTools);
            }

            @Override public java.util.Locale locale() {
                return userLocale();
            }

            @Override public ToolRegistry registry() {
                return new ToolRegistry(() -> System.currentTimeMillis() / 1000L, app.externalToolProviders());
            }

            @Override public boolean canAutoExecute(ToolRegistry.Request request) {
                if (request == null) return false;
                switch (request.tool) {
                    case CURRENT_TIME:
                    case CALCULATOR:
                    case UNIT_CONVERT:
                    case SEARCH_TOOLS:
                        return true;
                    case WEATHER:
                        return preferences.networkToolsEnabled();
                    case WEB_SEARCH:
                        return SearchEndpointPolicy.enabled(
                                preferences.raw().getBoolean("search_enabled", false),
                                preferences.raw().getString("search_endpoint", ""));
                    case LIST_EVENTS:
                        return app.checkSelfPermission(android.Manifest.permission.READ_CALENDAR)
                                == android.content.pm.PackageManager.PERMISSION_GRANTED;
                    case LOOKUP_CONTACT:
                        return app.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
                                == android.content.pm.PackageManager.PERMISSION_GRANTED;
                    case FS_LIST:
                    case FS_SEARCH:
                    case FS_READ: {
                        String wsUri = preferences.workspaceTreeUri();
                        return wsUri != null && !wsUri.trim().isEmpty();
                    }
                    default:
                        return false;
                }
            }

            @Override public AndroidActionExecutor.ActionResult executeReadOnly(ToolRegistry.Request request) {
                return app.createToolHandler().execute(request);
            }

            @Override public void proposeTool(ToolRegistry.Request request) {
                proposeTool(request, false);
            }

            @Override public void proposeTool(ToolRegistry.Request request, boolean tainted) {
                ToolCoordinator.Outcome outcome = tainted
                        ? app.toolCoordinator().proposeTainted(request, ToolCoordinator.Source.MODEL_PROPOSAL)
                        : app.toolCoordinator().propose(request, ToolCoordinator.Source.MODEL_PROPOSAL);
                if (outcome == null) return;
                String pickedLabel = preferences.calendarWriteLabel();
                main.post(() -> {
                    if ("PENDING_CONFIRMATION".equals(outcome.status) && outcome.proposal != null) {
                        showConsent(outcome.proposal.id,
                                ToolCoordinator.formatForDisplay(outcome.proposal.request, pickedLabel,
                                        outcome.proposal.tainted),
                                !outcome.proposal.tainted && outcome.proposal.risk == ToolCoordinator.Risk.SAFE);
                    }
                    if (ui != null && ui.isUsable()) {
                        ToolRegistry.Request req = outcome.proposal == null ? request : outcome.proposal.request;
                        ui.onToolOutcome(req, outcome);
                    }
                });
            }
        };
    }

    private void beginTurn(ChatSession.StartedTurn turn) throws Exception {
        if (history == null) history = new HistoryRepository(HistoryDatabase.get(app));
        String title = historyTitle;
        if (defaultHistoryTitle().equals(title)) {
            title = titleFor(turn.userText);
            historyTitle = title;
            preferences.raw().edit().putString("history_title", title).apply();
            String shown = title;
            main.post(() -> { if (ui != null && ui.isUsable()) ui.onHistoryTitle(shown); });
        }
        String id = conversationId == null || conversationId.isBlank()
                ? java.util.UUID.randomUUID().toString() : conversationId;
        conversationId = id;
        preferences.raw().edit()
                .putString("history_conversation_id", conversationId)
                .putString("history_title", historyTitle)
                .commit();
        String mediaRef = turn.mediaRef;
        if (mediaRef != null && !mediaRef.isBlank()) {
            mediaRef = HistoryMediaStore.persistPhoto(app, mediaRef, conversationId, turn.id);
            // Keep the live session pointing at the durable copy for follow-ups / retry.
            if (session != null && !mediaRef.isEmpty()) {
                // ChatSession already captured the cache path in the in-memory user row;
                // durable path is what Room stores for restore after death.
            }
        }
        history.beginTurn(new HistoryRepository.TurnStart(
                turn.id, id, historyTitle, turn.userText, turn.startedAt,
                mediaRef == null || mediaRef.isBlank() ? null : mediaRef));
    }

    private void saveTurn(ChatSession.CompletedTurn turn) {
        if (turn.assistantText != null && !turn.assistantText.isBlank()
                && turn.status != ChatSession.TerminalState.CANCELLED) {
            lastAssistant = turn.assistantText;
            main.post(() -> { if (ui != null && ui.isUsable()) ui.onAssistantReply(lastAssistant); });
            // Only COMPLETED replies become tool proposals. Cancelled text is inert.
            // Document content is DOCUMENT/untrusted and must not authorize tool proposals.
            if ("COMPLETED".equals(turn.status == null ? "" : turn.status.name())
                    && !turn.untrustedDocumentContext
                    && !turn.toolHandledInLoop) {
                proposeToolsFromAssistant(turn.assistantText);
            }
        }
        String title = historyTitle;
        if (defaultHistoryTitle().equals(title)) {
            title = titleFor(turn.userText);
            historyTitle = title;
            preferences.raw().edit().putString("history_title", title).apply();
            String shown = title;
            main.post(() -> { if (ui != null && ui.isUsable()) ui.onHistoryTitle(shown); });
        }
        String id = conversationId == null || conversationId.isBlank()
                ? java.util.UUID.randomUUID().toString() : conversationId;
        conversationId = id;
        if (history == null) history = new HistoryRepository(HistoryDatabase.get(app));
        String mediaRef = turn.mediaRef == null || turn.mediaRef.isBlank() ? null : turn.mediaRef;
        java.util.List<HistoryRepository.ToolExecution> executions = new java.util.ArrayList<>();
        if (turn.toolCalls != null) {
            for (ChatSnapshot.ToolCall call : turn.toolCalls) {
                if (call == null || call.running || call.pending) continue;
                String reqId = call.id == null || call.id.isBlank()
                        ? java.util.UUID.randomUUID().toString() : call.id;
                executions.add(new HistoryRepository.ToolExecution(
                        reqId,
                        call.toolName == null || call.toolName.isBlank() ? "tool" : call.toolName,
                        call.argumentsJson == null ? "{}" : call.argumentsJson,
                        call.succeeded,
                        call.status == null ? "" : call.status,
                        call.resultText == null ? "" : call.resultText,
                        turn.completedAt));
            }
        }
        history.completeTurn(new HistoryRepository.Turn(turn.id, id, historyTitle, turn.userText,
                turn.assistantText == null ? "" : turn.assistantText, turn.speechTranscript,
                HistoryRepository.Status.valueOf(turn.status.name()), turn.completedAt, null,
                turn.speechTranscriptStatus, mediaRef, turn.reasoning, executions));
    }

    private void onChat(String transcript, String status, boolean running) {
        boolean started = running && !lastGenerating;
        boolean stopped = !running && lastGenerating;
        lastTranscript = transcript == null ? "" : transcript;
        lastStatus = status == null ? "" : status;
        lastGenerating = running;
        if (started) {
            dispatchPolicy(() -> policy.onGenerationStarted(pendingQuestion, pendingMax, SystemClock.elapsedRealtime()));
            main.postDelayed(() -> {
                if (!unloading) dispatchPolicy(() -> policy.refreshRate(SystemClock.elapsedRealtime()));
            }, 1100L);
        }
        if (stopped) {
            dispatchPolicy(() -> policy.onGenerationFinished(SystemClock.elapsedRealtime()));
            if (app.startedActivityCount() == 0) {
                RuntimeNotifications.notifyReplyReady(app, conversationId, historyTitle, lastStatus);
            }
        }
        if (ui != null && ui.isUsable()) ui.onChat(lastTranscript, lastStatus, running);
    }

    private void onSnapshot(ChatSnapshot snapshot) {
        lastSnapshot = snapshot;
        if (ui != null && ui.isUsable()) ui.onSnapshot(snapshot);
    }

    private void onDecodedTokens(int count) {
        if (count <= 0) return;
        pendingTokens.addAndGet(count);
        if (tokenFlushPosted.compareAndSet(false, true)) {
            main.post(() -> {
                tokenFlushPosted.set(false);
                int n = pendingTokens.getAndSet(0);
                if (n > 0) dispatchPolicy(() -> policy.onTokens(n, SystemClock.elapsedRealtime()));
            });
        }
    }

    private void apply(RuntimePolicy.Effects effects) {
        if (unloading) return;
        if (effects.unloadModel) {
            String reason = lastPolicyUnloadReason;
            lastPolicyUnloadReason = "";
            if (reason == null || reason.isEmpty()) {
                reason = effects.cancelTurn ? "policy-cancel" : "policy";
            } else if (effects.cancelTurn) {
                reason = reason + "-cancel";
            }
            unload(reason, null);
            return;
        }
        last = effects;
        if (effects.wantWake) acquireWake();
        else releaseWake();
        publish(effects);
    }

    private void publish(RuntimePolicy.Effects effects) {
        if (effects == null) return;
        if (effects.wantWake) acquireWake();
        else if (!policy.generating()) releaseWake();
        if (!effects.wantService) {
            serviceStartRequested = false;
            releaseWake();
            if (service != null) service.stopNow();
            return;
        }
        if (service != null) {
            service.render(RuntimeNotifications.build(app, effects.notice, conversationId, historyTitle),
                    effects.notice);
            return;
        }
        if (!serviceStartRequested) {
            Intent intent = new Intent(app, ModelRuntimeService.class);
            serviceStartRequested = RuntimeServiceStarter.start(app, intent);
        }
    }

    private RuntimeNotice policyNotice() {
        return last == null || last.notice == null ? RuntimeNotice.hidden() : last.notice;
    }

    private boolean playbackActive() {
        PlaybackController.State state = playback.state();
        return state == PlaybackController.State.STARTING || state == PlaybackController.State.PLAYING
                || state == PlaybackController.State.STOPPING;
    }

    private void acquireWake() {
        if (wakeLock == null) {
            PowerManager manager = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (manager == null) return;
            wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lfm3pocket:generation");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) wakeLock.acquire(30L * 60L * 1000L);
    }

    private void releaseWake() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private void listenForHeat() {
        if (Build.VERSION.SDK_INT < 29) return;
        PowerManager manager = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        if (manager == null) return;
        manager.addThermalStatusListener(main::post, status ->
                dispatchPolicy(() -> {
                    if (status >= 3) lastPolicyUnloadReason = "policy-thermal";
                    return policy.onThermal(status, SystemClock.elapsedRealtime());
                }));
    }

    private void listenForBattery() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_BATTERY_LOW);
        filter.addAction(Intent.ACTION_BATTERY_OKAY);
        Intent sticky = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky != null) dispatchPolicy(() -> policy.onBatteryLow(isLow(sticky)));
        android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                boolean low = intent != null && (Intent.ACTION_BATTERY_LOW.equals(intent.getAction()) || isLow(intent));
                if (intent != null && Intent.ACTION_BATTERY_OKAY.equals(intent.getAction())) low = false;
                final boolean lowNow = low;
                dispatchPolicy(() -> policy.onBatteryLow(lowNow));
            }
        };
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(receiver, filter);
    }

    private static boolean isLow(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) return false;
        return (level * 100f) / scale < 15f;
    }

    private String titleFor(String question) {
        String fresh = defaultHistoryTitle();
        String title = question == null ? fresh : question.replaceAll("\\s+", " ").trim();
        if (title.length() > 60) title = title.substring(0, 57) + "…";
        return title.isEmpty() ? fresh : title;
    }

    private final class CountingEngine implements InferenceEngine {
        private final InferenceEngine delegate;
        CountingEngine(InferenceEngine delegate) { this.delegate = delegate; }
        @Override public void configure(InferenceSettings settings) {
            activeSettings = settings == null ? InferenceSettings.DEFAULT : settings;
            delegate.configure(activeSettings);
        }
        @Override public long loadModel(String modelPath, String projectorPath) {
            return delegate.loadModel(modelPath, projectorPath);
        }
        @Override public void unloadModel(long model) { delegate.unloadModel(model); }
        @Override public void releaseSession(long model) { delegate.releaseSession(model); }
        @Override public String generate(long model, String prompt, String mediaPath, int maxTokens, TokenSink sink) {
            return generateWithMedia(model, prompt, mediaPath == null ? new String[0] : new String[] { mediaPath },
                    maxTokens, sink);
        }
        @Override public String generateWithMedia(long model, String prompt, String[] mediaPaths, int maxTokens, TokenSink sink) {
            return generateWithMediaAndGrammar(model, prompt, mediaPaths, maxTokens, null, null, sink);
        }
        @Override public String generateWithMediaAndGrammar(long model, String prompt, String[] mediaPaths,
                                                            int maxTokens, String grammar, String grammarTrigger,
                                                            TokenSink sink) {
            return delegate.generateWithMediaAndGrammar(model, prompt, mediaPaths, maxTokens, grammar, grammarTrigger,
                    new TokenSink() {
                        @Override public void onToken(String piece) {
                            onDecodedTokens(1);
                            sink.onToken(piece);
                        }
                        @Override public void onProgress(String stage, int done, int total) {
                            sink.onProgress(stage, done, total);
                        }
                    });
        }
        @Override public String generateSpeech(long model, String prompt, SpeechOptions options, AudioSink sink) {
            return delegate.generateSpeech(model, prompt, options, sink);
        }
        @Override public boolean supportsVoiceAudioIn(long handle) { return delegate.supportsVoiceAudioIn(handle); }
        @Override public void stop() { delegate.stop(); }
        @Override public void resetStop() { delegate.resetStop(); }
    }
}
