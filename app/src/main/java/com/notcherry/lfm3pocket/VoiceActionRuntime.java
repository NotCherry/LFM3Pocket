package com.notcherry.lfm3pocket;

import android.content.SharedPreferences;

import java.io.File;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Voice-action loop: listen → one-pass Omni audio-in → optional tool proposal → spoken confirm.
 * Heavy logic stays out of {@link MainActivity}.
 */
public final class VoiceActionRuntime implements VoiceSpokenOutput.Host {
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
    private final VoiceSpokenOutput spoken;
    private final VoiceListenSession listen;
    private final ScheduledExecutorService listenTimeout = Executors.newSingleThreadScheduledExecutor();
    private volatile Mode mode = Mode.IDLE;
    private volatile String pendingConfirmId;
    private volatile String pendingConfirmDescribe;
    private volatile File pendingWav;

    private enum Mode { IDLE, COMMAND, CONFIRM }

    public VoiceActionRuntime(Callbacks callbacks, File recordingDir, BooleanSupplier micPermission,
                              java.util.concurrent.Executor recordWorker, Consumer<Runnable> uiPoster) {
        this.callbacks = callbacks;
        this.spoken = new VoiceSpokenOutput(this);
        RecordingController.Factory factory = () -> ((Lfm3PocketApplication) callbacks.app()).newRecordingSource();
        this.listen = new VoiceListenSession(recordingDir, factory, micPermission, recordWorker, uiPoster,
                listenTimeout, new VoiceListenSession.Listener() {
                    @Override public void changed(VoiceListenSession.State state, String message) {
                        if (state == VoiceListenSession.State.LISTENING) callbacks.policyListenStarted();
                        else if (state == VoiceListenSession.State.READY || state == VoiceListenSession.State.CANCELLED
                                || state == VoiceListenSession.State.TIMEOUT || state == VoiceListenSession.State.ERROR)
                            callbacks.policyListenFinished();
                        callbacks.onVoiceStatus().accept(message);
                    }

                    @Override public void wavReady(File wav) {
                        onWavReady(wav);
                    }
                }, VoiceListenLimits.MAX_ACTION_SAMPLES);
    }

    public boolean isEnabled() {
        return VoiceAudioCapability.voiceActionsEnabled(callbacks.modelPrefs(), callbacks.modelHandle());
    }

    public boolean isListening() {
        return listen.state() == VoiceListenSession.State.LISTENING;
    }

    public void beginShadeTalk() {
        if (callbacks.modelHandle() == 0L || callbacks.isGenerating()) return;
        if (!((Lfm3PocketApplication) callbacks.app()).hasMicrophonePermission()) {
            spoken.speakExact(callbacks.app().getString(R.string.voice_mic_required),
                    callbacks.userLocale(), null);
            return;
        }
        if (!isEnabled()) {
            spoken.speakExact(disabledReason(), callbacks.userLocale(), null);
            return;
        }
        mode = Mode.COMMAND;
        listen.startTimed(VoiceListenLimits.TALK_TIMEOUT_MS);
    }

    public boolean pttPressed() {
        if (!isEnabled() || callbacks.modelHandle() == 0L || callbacks.isGenerating()) return false;
        mode = Mode.COMMAND;
        return listen.startPtt();
    }

    public void pttReleased() {
        if (listen.state() == VoiceListenSession.State.LISTENING) listen.stopPtt();
    }

    public void cancelAll(String reason) {
        spoken.stop();
        listen.cancel(reason);
        mode = Mode.IDLE;
        deletePending();
    }

    void onWavReady(File wav) {
        if (wav == null || !wav.isFile()) return;
        pendingWav = wav;
        if (mode == Mode.CONFIRM) {
            callbacks.worker().execute(this::runConfirmPass);
        } else {
            callbacks.policyVoiceThinking();
            callbacks.onVoiceStatus().accept("Thinking…");
            callbacks.worker().execute(this::runCommandPass);
        }
    }

    private void runCommandPass() {
        File wav = pendingWav;
        pendingWav = null;
        if (wav == null) return;
        try {
            maybeTrim(wav);
            String prompt = VoiceToolPromptCard.onePassAudioPrompt(callbacks.userLocale());
            StringBuilder reply = new StringBuilder();
            callbacks.engine().resetStop();
            String err = callbacks.engine().generateWithMedia(callbacks.modelHandle(), prompt,
                    new String[] { wav.getAbsolutePath() }, 512, reply::append);
            String assistant = ThinkingSanitizer.clean(reply.toString()).answer;
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
        } finally {
            wav.delete();
            callbacks.main().post(callbacks::policyGenerationFinished);
        }
    }

    private void beginVoiceConfirm(ToolCoordinator.Proposal proposal) {
        pendingConfirmId = proposal.id;
        pendingConfirmDescribe = ToolCoordinator.describe(proposal.request);
        mode = Mode.CONFIRM;
        callbacks.showConsent(proposal.id, pendingConfirmDescribe, proposal.risk == ToolCoordinator.Risk.SAFE);
        String prompt = VoiceSpokenOutput.confirmSpokenPrompt(pendingConfirmDescribe, callbacks.userLocale());
        spoken.speakExact(prompt, callbacks.userLocale(), () -> {
            mode = Mode.CONFIRM;
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
            maybeTrim(wav);
            StringBuilder recognized = new StringBuilder();
            callbacks.engine().resetStop();
            callbacks.engine().generateWithMedia(callbacks.modelHandle(), VoiceToolPromptCard.confirmAsrPrompt(),
                    new String[] { wav.getAbsolutePath() }, 64, recognized::append);
            VoiceConfirmLexicon.Decision decision = VoiceConfirmLexicon.matchTranscript(
                    ThinkingSanitizer.clean(recognized.toString()).answer);
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
                spoken.speakExact(callbacks.userLocale().getLanguage().startsWith("pl")
                        ? "Anulowano." : "Cancelled.", callbacks.userLocale(), null);
            }
            callbacks.clearConsent();
        } finally {
            wav.delete();
            mode = Mode.IDLE;
        }
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
        return callbacks.app().getString(R.string.voice_no_audio_model);
    }

    private void deletePending() {
        if (pendingWav != null) {
            pendingWav.delete();
            pendingWav = null;
        }
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