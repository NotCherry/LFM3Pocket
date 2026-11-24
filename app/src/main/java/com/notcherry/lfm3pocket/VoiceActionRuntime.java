package com.notcherry.lfm3pocket;

import android.content.SharedPreferences;

import java.io.File;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Voice-action loop: listen → one-pass Omni audio-in or STT text → optional tool proposal → spoken confirm.
 * Heavy logic stays out of {@link MainActivity}.
 */
public final class VoiceActionRuntime implements VoiceSpokenOutput.Host, AutoCloseable {
    public interface Callbacks {
        long modelHandle();
        InferenceEngine engine();
        PlaybackController playback();
        java.util.concurrent.ExecutorService worker();
        android.os.Handler main();
        android.content.Context app();
        SharedPreferences modelPrefs();
        ToolCoordinator toolCoordinator();
        boolean isGenerating();
        void showConsent(String proposalId, String describe, boolean safe);
        void clearConsent();
        void resolveConsent(String proposalId, boolean approve);
        void stopTurn();
        Locale userLocale();
        Consumer<String> onVoiceStatus();
        void policyListenStarted();
        void policyListenFinished();
        void policyVoiceThinking();
        void policySpeechStarted();
        void policyPlaybackStarted();
        void policySpeechFinished();
        void policyGenerationFinished();
    }

    private final Callbacks callbacks;
    private final BooleanSupplier micPermission;
    private final VoiceSpokenOutput spoken;
    private final VoiceListenSession listen;
    private final ScheduledExecutorService listenTimeout = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean transcriptionCancelled = new AtomicBoolean();
    private volatile Mode mode = Mode.IDLE;
    private volatile String pendingConfirmId;
    private volatile String pendingConfirmDescribe;
    private volatile File pendingWav;

    private enum Mode { IDLE, COMMAND, CONFIRM }

    public VoiceActionRuntime(Callbacks callbacks, File recordingDir, BooleanSupplier micPermission,
                              java.util.concurrent.Executor recordWorker, Consumer<Runnable> uiPoster) {
        this(callbacks, recordingDir, micPermission, recordWorker, uiPoster, null);
    }

    VoiceActionRuntime(Callbacks callbacks, File recordingDir, BooleanSupplier micPermission,
                       java.util.concurrent.Executor recordWorker, Consumer<Runnable> uiPoster,
                       RecordingController.Factory recordingFactory) {
        this.callbacks = callbacks;
        this.micPermission = micPermission;
        this.spoken = new VoiceSpokenOutput(this);
        RecordingController.Factory factory = recordingFactory != null
                ? recordingFactory
                : () -> ((Lfm3PocketApplication) callbacks.app()).newRecordingSource();
        this.listen = new VoiceListenSession(recordingDir, factory, micPermission, recordWorker, uiPoster,
                listenTimeout, new VoiceListenSession.Listener() {
                    @Override public void changed(VoiceListenSession.State state, String message) {
                        if (state == VoiceListenSession.State.LISTENING) callbacks.policyListenStarted();
                        else if (state == VoiceListenSession.State.READY || state == VoiceListenSession.State.CANCELLED
                                || state == VoiceListenSession.State.TIMEOUT || state == VoiceListenSession.State.ERROR) {
                            callbacks.policyListenFinished();
                            if (mode == Mode.CONFIRM && state != VoiceListenSession.State.READY) {
                                rejectPendingConfirm();
                            }
                        }
                        if (state != VoiceListenSession.State.READY && state != VoiceListenSession.State.IDLE) {
                            callbacks.onVoiceStatus().accept(message);
                        }
                    }

                    @Override public void wavReady(File wav) {
                        onWavReady(wav);
                    }
                }, VoiceListenLimits.MAX_ACTION_SAMPLES);
    }

    public boolean isEnabled() {
        android.content.Context app = callbacks.app();
        if (app == null) {
            return VoiceAudioCapability.voiceActionsEnabled(callbacks.engine(), callbacks.modelHandle());
        }
        return VoiceAudioCapability.voiceActionsEnabled(app, callbacks.engine(), callbacks.modelHandle());
    }

    public boolean isListening() {
        return listen.state() == VoiceListenSession.State.LISTENING;
    }

    public void beginShadeTalk() {
        if (!prepareVoiceCommandStart()) return;
        preemptForNewCommand("shade talk");
        mode = Mode.COMMAND;
        listen.startTimed(VoiceListenLimits.TALK_TIMEOUT_MS);
    }

    /** TalkBack toggle: same 8 s cap as shade Talk. */
    public boolean beginAccessibilityTalk() {
        if (!prepareVoiceCommandStart()) return false;
        preemptForNewCommand("accessibility talk");
        mode = Mode.COMMAND;
        return listen.startTimed(VoiceListenLimits.TALK_TIMEOUT_MS);
    }

    public boolean pttPressed() {
        if (!prepareVoiceCommandStart()) return false;
        preemptForNewCommand("ptt");
        mode = Mode.COMMAND;
        return listen.startPtt();
    }

    /**
     * Shared gate for shade Talk, PTT, and accessibility Talk. Publishes a user-visible
     * status when microphone permission or an in-flight chat reply blocks voice capture.
     */
    private boolean prepareVoiceCommandStart() {
        if (callbacks.modelHandle() == 0L) return false;
        if (!micPermission.getAsBoolean()) {
            publishMicRequiredStatus();
            return false;
        }
        if (callbacks.isGenerating()) {
            publishReplyRunningStatus();
            return false;
        }
        if (!isEnabled()) {
            callbacks.onVoiceStatus().accept(disabledReason());
            return false;
        }
        return true;
    }

    private void publishMicRequiredStatus() {
        android.content.Context app = callbacks.app();
        String message = app == null
                ? "Microphone permission is required for Talk."
                : app.getString(R.string.voice_mic_required);
        callbacks.onVoiceStatus().accept(message);
    }

    private void publishReplyRunningStatus() {
        android.content.Context app = callbacks.app();
        String message = app == null
                ? "Finish the current reply before using Talk."
                : app.getString(R.string.voice_talk_reply_running);
        callbacks.onVoiceStatus().accept(message);
    }

    public void pttReleased() {
        if (listen.state() == VoiceListenSession.State.LISTENING) listen.stopPtt();
    }

    public void cancelAll(String reason) {
        transcriptionCancelled.set(true);
        spoken.stop();
        listen.cancel(reason);
        if (mode == Mode.CONFIRM) rejectPendingConfirm();
        mode = Mode.IDLE;
        deletePending();
    }

    void onPlaybackIdle() {
        spoken.onPlaybackIdle();
    }

    private void preemptForNewCommand(String reason) {
        transcriptionCancelled.set(true);
        String id = pendingConfirmId;
        pendingConfirmId = null;
        pendingConfirmDescribe = null;
        if (id != null) {
            callbacks.resolveConsent(id, false);
            callbacks.clearConsent();
        }
        spoken.stop();
        listen.cancel(reason);
        deletePending();
        transcriptionCancelled.set(false);
    }

    private void rejectPendingConfirm() {
        String id = pendingConfirmId;
        if (id == null) return;
        spoken.invalidateDeferredCompletions();
        pendingConfirmId = null;
        pendingConfirmDescribe = null;
        mode = Mode.IDLE;
        callbacks.resolveConsent(id, false);
        callbacks.clearConsent();
    }

    VoiceListenSession listenSessionForTests() { return listen; }

    void onWavReady(File wav) {
        if (wav == null || !wav.isFile()) return;
        pendingWav = wav;
        if (mode == Mode.CONFIRM) {
            callbacks.worker().execute(this::runConfirmPass);
        } else {
            callbacks.policyVoiceThinking();
            callbacks.onVoiceStatus().accept(callbacks.app() == null
                    ? "Thinking…"
                    : callbacks.app().getString(usesSttInput()
                        ? R.string.speech_input_transcribing
                        : R.string.voice_thinking));
            callbacks.worker().execute(this::runCommandPass);
        }
    }

    private void runCommandPass() {
        File wav = pendingWav;
        pendingWav = null;
        if (wav == null) return;
        try {
            maybeTrim(wav);
            if (usesSttInput()) {
                runSttCommandPass(wav);
                return;
            }
            String prompt = VoiceToolPromptCard.onePassAudioPrompt(callbacks.userLocale());
            StringBuilder reply = new StringBuilder();
            callbacks.engine().resetStop();
            String err = callbacks.engine().generateWithMedia(callbacks.modelHandle(), prompt,
                    new String[] { wav.getAbsolutePath() }, 512, reply::append);
            handleAssistantReply(reply.toString(), err);
        } finally {
            wav.delete();
            callbacks.main().post(callbacks::policyGenerationFinished);
        }
    }

    private void runSttCommandPass(File wav) {
        SttResult result = transcribeWav(wav);
        if (!result.isOk()) {
            surfaceSttFailure(result);
            return;
        }
        String prompt = VoiceToolPromptCard.onePassTextPrompt(callbacks.userLocale(), result.text);
        StringBuilder reply = new StringBuilder();
        callbacks.engine().resetStop();
        String err = callbacks.engine().generate(callbacks.modelHandle(), prompt, null, 512, reply::append);
        handleAssistantReply(reply.toString(), err);
    }

    private void handleAssistantReply(String rawAssistant, String err) {
        String assistant = ThinkingSanitizer.clean(rawAssistant).answer;
        if (err != null && assistant.isEmpty()) {
            speakStatus(err);
            return;
        }
        ToolCoordinator.Outcome outcome = ModelToolBridge.proposeFromAssistant(
                new ToolRegistry(), callbacks.toolCoordinator(), assistant);
        if (outcome != null && "PENDING_CONFIRMATION".equals(outcome.status) && outcome.proposal != null) {
            beginVoiceConfirm(outcome.proposal);
            return;
        }
        String toSpeak = assistant.isEmpty() ? (err != null ? err : "No reply.") : assistant;
        spoken.speakExact(toSpeak, callbacks.userLocale(), null);
    }

    private void beginVoiceConfirm(ToolCoordinator.Proposal proposal) {
        pendingConfirmId = proposal.id;
        pendingConfirmDescribe = ToolCoordinator.describe(proposal.request);
        mode = Mode.CONFIRM;
        callbacks.showConsent(proposal.id, pendingConfirmDescribe, proposal.risk == ToolCoordinator.Risk.SAFE);
        String prompt = VoiceSpokenOutput.confirmSpokenPrompt(pendingConfirmDescribe, callbacks.userLocale());
        final long armGeneration = spoken.currentSpeechGeneration();
        final String confirmId = proposal.id;
        spoken.speakExact(prompt, callbacks.userLocale(), () -> {
            if (spoken.currentSpeechGeneration() != armGeneration) return;
            if (!confirmId.equals(pendingConfirmId) || mode != Mode.CONFIRM) return;
            listen.cancel("arming confirm");
            listen.startTimed(VoiceListenLimits.MAX_CONFIRM_SECONDS * 1000);
        });
    }

    private void runConfirmPass() {
        File wav = pendingWav;
        pendingWav = null;
        if (wav == null || pendingConfirmId == null) {
            if (wav != null) wav.delete();
            return;
        }
        try {
            spoken.invalidateDeferredCompletions();
            maybeTrim(wav);
            String err = null;
            VoiceConfirmLexicon.Decision decision;
            if (usesSttInput()) {
                SttResult result = transcribeWav(wav);
                if (result.status == SttResult.Status.CANCELLED) {
                    rejectPendingConfirm();
                    return;
                }
                if (!result.isOk()) {
                    String message = result.message == null || result.message.isEmpty() ? "stt failed" : result.message;
                    rejectPendingConfirm();
                    callbacks.onVoiceStatus().accept(message);
                    return;
                }
                decision = VoiceConfirmLexicon.matchTranscript(result.text);
            } else {
                StringBuilder recognized = new StringBuilder();
                callbacks.engine().resetStop();
                err = callbacks.engine().generateWithMedia(callbacks.modelHandle(),
                        VoiceToolPromptCard.confirmAsrPrompt(), new String[] { wav.getAbsolutePath() },
                        64, recognized::append);
                // Authorization must fail closed: partial output from a failed/cancelled decode
                // is not a valid confirmation, even if it happens to contain "yes" or "tak".
                if (err != null && !err.isBlank()) {
                    rejectPendingConfirm();
                    callbacks.onVoiceStatus().accept(err);
                    return;
                }
                String transcript = ThinkingSanitizer.clean(recognized.toString()).answer;
                decision = VoiceConfirmLexicon.matchTranscript(transcript);
            }
            String id = pendingConfirmId;
            pendingConfirmId = null;
            pendingConfirmDescribe = null;
            mode = Mode.IDLE;
            if (decision == VoiceConfirmLexicon.Decision.APPROVE) {
                callbacks.resolveConsent(id, true);
                spoken.speakExact(callbacks.userLocale().getLanguage().startsWith("pl")
                        ? "Zatwierdzono." : "Approved.", callbacks.userLocale(), null);
            } else {
                callbacks.resolveConsent(id, false);
                spoken.speakExact(err != null
                        ? (callbacks.userLocale().getLanguage().startsWith("pl")
                                ? "Nie udało się potwierdzić." : "Confirmation failed.")
                        : (callbacks.userLocale().getLanguage().startsWith("pl")
                                ? "Anulowano." : "Cancelled."),
                        callbacks.userLocale(), null);
            }
            callbacks.clearConsent();
        } finally {
            wav.delete();
            mode = Mode.IDLE;
        }
    }

    private SttResult transcribeWav(File wav) {
        android.content.Context app = callbacks.app();
        if (app == null) return SttResult.unavailable("Speech input is unavailable");
        transcriptionCancelled.set(false);
        return SpeechInputProviders.current(app).transcribe(app, wav, transcriptionCancelled);
    }

    private void surfaceSttFailure(SttResult result) {
        android.content.Context app = callbacks.app();
        if (app == null) {
            speakStatus(result == null ? "Speech transcription failed" : result.message);
            return;
        }
        String message;
        if (result == null) {
            message = app.getString(R.string.speech_input_transcript_failed, "unknown error");
        } else if (result.status == SttResult.Status.EMPTY) {
            message = result.message == null || result.message.isEmpty()
                    ? app.getString(R.string.speech_input_transcript_empty)
                    : result.message;
        } else if (result.status == SttResult.Status.CANCELLED) {
            return;
        } else {
            String detail = result.message == null || result.message.isEmpty() ? "unknown error" : result.message;
            message = app.getString(R.string.speech_input_transcript_failed, detail);
        }
        callbacks.onVoiceStatus().accept(message);
        speakStatus(message);
    }

    boolean usesSttInput() {
        android.content.Context app = callbacks.app();
        return app != null
                && new RuntimePreferences(app).speechInputMode() == SpeechInputMode.CACTUS_WHISTLE_STT;
    }

    private void maybeTrim(File wav) {
        try {
            byte[] raw = Files.readAllBytes(wav.toPath());
            byte[] trimmed = VoiceSilenceTrim.trimWav(raw, 400);
            if (trimmed != raw && trimmed.length > 44) Files.write(wav.toPath(), trimmed);
        } catch (Exception ignored) {
            // Trimming is optional; never fail the turn.
        }
    }

    private void speakStatus(String text) {
        spoken.speakExact(text, callbacks.userLocale(), null);
    }

    String disabledReason() {
        if (callbacks.modelHandle() == 0L) return "Load a model first.";
        android.content.Context app = callbacks.app();
        if (app != null) {
            String reason = VoiceAudioCapability.disabledReason(app, callbacks.engine(), callbacks.modelHandle());
            if (reason != null && !reason.isEmpty()) return reason;
        }
        return callbacks.app() == null ? "This model cannot hear audio" : callbacks.app().getString(R.string.voice_no_audio_model);
    }

    private void deletePending() {
        if (pendingWav != null) {
            pendingWav.delete();
            pendingWav = null;
        }
    }


    @Override public void close() {
        cancelAll("Closed");
        listen.close();
        listenTimeout.shutdownNow();
    }

    @Override public long modelHandle() { return callbacks.modelHandle(); }
    @Override public InferenceEngine engine() { return callbacks.engine(); }
    @Override public PlaybackController playback() { return callbacks.playback(); }
    @Override public java.util.concurrent.Executor worker() { return callbacks.worker(); }
    @Override public android.os.Handler main() { return callbacks.main(); }
    @Override public android.content.Context app() { return callbacks.app(); }

    @Override public void onSpeechUi(boolean active, String status) {
        callbacks.onVoiceStatus().accept(status == null ? "" : status);
    }

    @Override public void onPlaybackPolicy(boolean playing) {
        if (playing) callbacks.policyPlaybackStarted();
        else callbacks.policySpeechFinished();
    }

    @Override public void policySpeechStarted() { callbacks.policySpeechStarted(); }
}
