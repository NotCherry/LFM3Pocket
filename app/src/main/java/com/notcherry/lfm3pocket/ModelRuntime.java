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
    private volatile String loadedPath = "";
    private volatile String conversationId = "";
    private volatile String historyTitle;
    private volatile String lastAssistant = "";
    private volatile boolean speechCancel;
    private boolean playbackNoted;
    private boolean serviceStartRequested;
    private boolean unloading;
    private int sessionEpoch;
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
            dispatchPolicy(() -> policy.onClock(SystemClock.elapsedRealtime()));
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
        if (id != null && !id.isBlank()) conversationId = id;
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

    @Override public boolean isGenerating() { return session != null && session.isGenerating(); }

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

    public void load(String path, String projector, LoadCallback callback) {
        dispatchPolicy(() -> policy.onLoading(path == null ? "" : new File(path).getName()));
        worker.execute(() -> loadBlocking(path, projector, callback));
    }

    /** Caller is the runtime worker. Safe for import, which already runs there. */
    public boolean loadBlocking(String path, String projector, LoadCallback callback) {
        String name = path == null ? "" : new File(path).getName();
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
            preferences.raw().edit().putString("model", path).apply();
        }
        String status = failure == null
                ? (projector == null || projector.isEmpty()
                    ? "Text model ready \u0007 offline"
                    : "Model and projector loaded • offline")
                : "Model error: " + failure;
        main.post(() -> {
            if (loaded != 0L) {
                dispatchPolicy(() -> policy.onLoaded(name, SystemClock.elapsedRealtime()));
                dispatchPolicy(() -> policy.onVoiceTalkAvailable(voiceActions.isEnabled()));
            } else {
                dispatchPolicy(policy::onLoadFailed);
            }
            if (callback != null) callback.finished(status);
        });
        return loaded != 0L;
    }

    public void unload(String cause, Runnable done) {
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
                if (done != null) done.run();
            });
        });
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

    public void clearConversation() {
        if (session != null) session.clear();
        lastAssistant = "";
        if (session != null) {
            lastSnapshot = session.snapshot();
            lastTranscript = FlatTranscript.format(lastSnapshot);
            lastStatus = lastSnapshot == null ? "" : lastSnapshot.status;
            lastGenerating = session.isGenerating();
        } else {
            lastSnapshot = null;
            lastTranscript = FlatTranscriptFormatter.EMPTY_TRANSCRIPT;
            lastStatus = "";
            lastGenerating = false;
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

    /**
     * Force a Room rehydrate after conversation id changes on a cold process.
     * Room work runs on the runtime worker; the live session is updated on the main thread.
     */
    public void restoreOpenConversation() {
        ensureSession();
        final String id = conversationId;
        final int born = sessionEpoch;
        worker.execute(() -> {
            if (history == null) history = new HistoryRepository(HistoryDatabase.get(app));
            if (id == null || id.isBlank()) return;
            java.util.List<HistoryRepository.StoredMessage> rows;
            try {
                rows = history.messages(id);
            } catch (Exception ignored) {
                return;
            }
            if (rows.isEmpty()) return;
            java.util.ArrayList<ChatSession.RestoredMessage> restored = new java.util.ArrayList<>();
            for (HistoryRepository.StoredMessage row : rows) {
                restored.add(new ChatSession.RestoredMessage(
                        row.getRole(), row.getText(), row.getTurnId(), row.getTurnStatus(), row.getMediaRef()));
            }
            HistoryRepository.ConversationSummary summaryState = null;
            try {
                summaryState = history.conversationSummary(id);
            } catch (Exception ignored) {
            }
            final String summary = summaryState == null ? "" : summaryState.getSummary();
            final int summarizedCount = summaryState == null ? 0 : (int) Math.min(Integer.MAX_VALUE, summaryState.getSummarizedMessageCount());
            main.post(() -> {
                if (born != sessionEpoch || session == null) return;
                if (session.isGenerating()) return;
                ChatSnapshot existing = session.snapshot();
                if (existing != null && !existing.turns.isEmpty()) return;
                session.restore(restored, summary, summarizedCount);
                lastSnapshot = session.snapshot();
                lastTranscript = FlatTranscript.format(lastSnapshot);
                lastStatus = lastSnapshot == null ? "" : lastSnapshot.status;
                lastGenerating = session.isGenerating();
                Ui target = ui;
                if (target != null && target.isUsable()) {
                    if (lastSnapshot != null) target.onSnapshot(lastSnapshot);
                    target.onChat(lastTranscript, lastStatus, lastGenerating);
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
        if (handle == 0L) return;
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
                generateSystemSpeech(spoken);
                return;
            }
            if (backend == SpeechReplyBackend.TINY_VOCA) {
                main.post(() -> {
                    if (ui != null && ui.isUsable()) ui.onSpeech(true, "Speaking with English tiny-voca…");
                });
                generateTinyVocaSpeech(spoken);
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

    public void onAppBackgrounded() {
        if (!preferences.keepLoaded()) unload("left", null);
    }

    public void onChatFinished() {
        if (!preferences.keepLoaded()) unload("chat-closed", null);
    }

    public void onTrimMemory(int level) {
        main.post(() -> {
            boolean complete = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE;
            boolean critical = level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL;
            // Always drop the text KV/hybrid session under pressure (ADR 0001).
            // Weights may still unload via policy below.
            long current = handle;
            if (current != 0L && (complete || critical
                    || level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)) {
                worker.execute(() -> {
                    if (engine instanceof NativeInferenceEngine) {
                        ((NativeInferenceEngine) engine).releaseSession(current);
                    } else {
                        NativeBridge.releaseSession(current);
                    }
                });
            }
            if (!complete && !critical) {
                app.diagnostics().record(LocalDiagnostics.Kind.MEMORY, 0L, "trim=" + level);
                return;
            }
            dispatchPolicy(() -> policy.onTrim(complete, SystemClock.elapsedRealtime()));
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
        if (service == stopped) service = null;
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
        noteSubmission(text.trim(), 512);
        if (!session.submit(text.trim(), java.util.Collections.emptyList(), false, "", 512)) {
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

    private void generateSystemSpeech(String reply) {
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
        systemSpeech.speak(java.util.Locale.US, reply, new AndroidSystemSpeechSynthesizer.Callback() {
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
                            : "tiny-voca failed; using system voice…");
                }
                worker.execute(() -> generateSystemSpeech(reply));
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
            @Override public void updatePartial(String turnId, String assistantText, long atMs) {
                if (history == null) history = new HistoryRepository(HistoryDatabase.get(app));
                history.updateTurnPartial(turnId, assistantText == null ? "" : assistantText, atMs);
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
        if (turn.assistantText != null && !turn.assistantText.isBlank()) {
            lastAssistant = turn.assistantText;
            main.post(() -> { if (ui != null && ui.isUsable()) ui.onAssistantReply(lastAssistant); });
            // Only COMPLETED replies become tool proposals. Cancelled text is inert.
            // Document content is DOCUMENT/untrusted and must not authorize tool proposals.
            if ("COMPLETED".equals(turn.status == null ? "" : turn.status.name())
                    && !turn.untrustedDocumentContext) {
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
        history.completeTurn(new HistoryRepository.Turn(turn.id, id, historyTitle, turn.userText,
                turn.assistantText == null ? "" : turn.assistantText, turn.speechTranscript,
                HistoryRepository.Status.valueOf(turn.status.name()), turn.completedAt, null,
                turn.speechTranscriptStatus, mediaRef));
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
            unload(effects.cancelTurn ? "policy-cancel" : "policy", null);
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
            serviceStartRequested = true;
            Intent intent = new Intent(app, ModelRuntimeService.class);
            RuntimeServiceStarter.start(app, intent);
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
                dispatchPolicy(() -> policy.onThermal(status, SystemClock.elapsedRealtime())));
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
                dispatchPolicy(() -> policy.onBatteryLow(low));
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
        @Override public void configure(InferenceSettings settings) { delegate.configure(settings); }
        @Override public long loadModel(String modelPath, String projectorPath) {
            return delegate.loadModel(modelPath, projectorPath);
        }
        @Override public void unloadModel(long model) { delegate.unloadModel(model); }
        @Override public String generate(long model, String prompt, String mediaPath, int maxTokens, TokenSink sink) {
            return generateWithMedia(model, prompt, mediaPath == null ? new String[0] : new String[] { mediaPath },
                    maxTokens, sink);
        }
        @Override public String generateWithMedia(long model, String prompt, String[] mediaPaths, int maxTokens, TokenSink sink) {
            return delegate.generateWithMedia(model, prompt, mediaPaths, maxTokens, piece -> {
                onDecodedTokens(1);
                sink.onToken(piece);
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
