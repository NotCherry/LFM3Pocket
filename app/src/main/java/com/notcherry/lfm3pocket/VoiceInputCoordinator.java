package com.notcherry.lfm3pocket;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.widget.Button;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Coordinates voice recording, Cactus Whistle STT transcription, spoken reply playback,
 * hold-to-talk voice PTT, and assistant voice-session bootstrap outside {@link MainActivity} (#322).
 */
final class VoiceInputCoordinator {
    static final class Deps {
        final Activity activity;
        final ModelRuntime runtime;
        final PlaybackController playbackController;
        final Handler mainHandler;
        final Supplier<SharedPreferences> preferences;
        final Supplier<ChatPanel> chatPanel;
        final BooleanSupplier isDestroyed;
        final BooleanSupplier isForeground;
        final BooleanSupplier isBusy;
        final BooleanSupplier isGenerating;
        final BooleanSupplier isSpeaking;
        final BooleanSupplier isSettingsLoaded;
        final Supplier<String> lastAssistantReply;
        final Consumer<Boolean> setBusy;
        final Consumer<Boolean> setSpeaking;
        final Runnable recomputeBusy;
        final Consumer<CharSequence> setChatStatus;
        final Consumer<File> onAudioAttachmentRecorded;
        final Consumer<String> sendText;
        final Runnable requestAudioPermission;
        final Runnable updateControls;

        Deps(Activity activity, ModelRuntime runtime, PlaybackController playbackController,
                Handler mainHandler, Supplier<SharedPreferences> preferences,
                Supplier<ChatPanel> chatPanel, BooleanSupplier isDestroyed,
                BooleanSupplier isForeground, BooleanSupplier isBusy,
                BooleanSupplier isGenerating, BooleanSupplier isSpeaking,
                BooleanSupplier isSettingsLoaded, Supplier<String> lastAssistantReply,
                Consumer<Boolean> setBusy, Consumer<Boolean> setSpeaking,
                Runnable recomputeBusy, Consumer<CharSequence> setChatStatus,
                Consumer<File> onAudioAttachmentRecorded, Consumer<String> sendText,
                Runnable requestAudioPermission, Runnable updateControls) {
            this.activity = activity;
            this.runtime = runtime;
            this.playbackController = playbackController;
            this.mainHandler = mainHandler;
            this.preferences = preferences;
            this.chatPanel = chatPanel;
            this.isDestroyed = isDestroyed;
            this.isForeground = isForeground;
            this.isBusy = isBusy;
            this.isGenerating = isGenerating;
            this.isSpeaking = isSpeaking;
            this.isSettingsLoaded = isSettingsLoaded;
            this.lastAssistantReply = lastAssistantReply;
            this.setBusy = setBusy;
            this.setSpeaking = setSpeaking;
            this.recomputeBusy = recomputeBusy;
            this.setChatStatus = setChatStatus;
            this.onAudioAttachmentRecorded = onAudioAttachmentRecorded;
            this.sendText = sendText;
            this.requestAudioPermission = requestAudioPermission;
            this.updateControls = updateControls;
        }
    }

    private final Deps deps;
    private final ExecutorService audioWorker = Executors.newSingleThreadExecutor();
    private final RecordingController recordingController;
    private final AtomicBoolean voiceSttCancelled = new AtomicBoolean();
    private VoicePttCoordinator voicePtt;
    private volatile boolean transcribingVoiceInput;
    private boolean pendingAssistantVoiceSession;
    private boolean microphoneRequested;

    VoiceInputCoordinator(Deps deps) {
        this.deps = deps;
        Lfm3PocketApplication app = (Lfm3PocketApplication) deps.activity.getApplication();
        this.recordingController = new RecordingController(
                new File(deps.activity.getCacheDir(), "recordings"),
                app::newRecordingSource,
                app::hasMicrophonePermission,
                audioWorker,
                deps.mainHandler::post,
                (captureState, message, completed) -> {
                    if (deps.isDestroyed.getAsBoolean()) {
                        if (completed != null) completed.delete();
                        return;
                    }
                    if (completed != null && SpeechInputProviders.usesSttTextPipeline(deps.activity)) {
                        handleCompletedRecordingAsStt(completed);
                        return;
                    }
                    deps.recomputeBusy.run();
                    deps.setChatStatus.accept(message);
                    if (completed != null) deps.onAudioAttachmentRecorded.accept(completed);
                    deps.updateControls.run();
                });
    }

    void bindRecordButton(Button recordButton) {
        voicePtt = new VoicePttCoordinator(deps.runtime, () -> {
            if (!((Lfm3PocketApplication) deps.activity.getApplication()).hasMicrophonePermission()) {
                microphoneRequested = true;
                deps.requestAudioPermission.run();
            }
        });
        recordButton.setOnClickListener(v -> {
            if (useVoicePttInChat() && voicePtt.onClick(v)) return;
            if (useVoicePttInChat()) return;
            toggleRecording();
        });
        recordButton.setOnTouchListener((v, event) -> useVoicePttInChat() && voicePtt.onTouch(v, event));
    }

    /**
     * In Cactus Whistle STT mode, Chat's Voice input button always uses tap-to-record -> local STT
     * -> normal text chat pipeline so transcribed turns appear in Chat and History. Hold-to-talk
     * VoicePttCoordinator stays active only for LFM3-Omni voice actions.
     */
    boolean useVoicePttInChat() {
        if (SpeechInputProviders.usesSttTextPipeline(deps.activity)) return false;
        return deps.runtime != null && deps.runtime.voice().isEnabled();
    }

    boolean recordingActive() {
        RecordingController.State state = recordingController.state();
        return state == RecordingController.State.STARTING || state == RecordingController.State.RECORDING
                || state == RecordingController.State.STOPPING;
    }

    boolean playbackActive() {
        PlaybackController.State state = deps.playbackController.state();
        return state == PlaybackController.State.STARTING || state == PlaybackController.State.PLAYING
                || state == PlaybackController.State.STOPPING;
    }

    boolean isTranscribing() {
        return transcribingVoiceInput;
    }

    void cancelAllOnClear() {
        voiceSttCancelled.set(true);
        transcribingVoiceInput = false;
        if (recordingActive()) recordingController.cancel();
    }

    boolean stopActiveWorkIfHandling() {
        if (transcribingVoiceInput) {
            voiceSttCancelled.set(true);
            transcribingVoiceInput = false;
            deps.setBusy.accept(false);
            deps.setChatStatus.accept("Speech transcription cancelled.");
            deps.updateControls.run();
            return true;
        }
        if (recordingActive()) {
            recordingController.stop();
            return true;
        }
        return false;
    }

    void paintVoiceControls(Button recordButton, Button speakButton, String voiceUiStatus) {
        boolean busy = deps.isBusy.getAsBoolean();
        boolean speaking = deps.isSpeaking.getAsBoolean();
        RecordingController.State capture = recordingController.state();
        boolean voiceMic = useVoicePttInChat();
        boolean voiceListening = deps.runtime != null && deps.runtime.voice().isEnabled()
                && deps.runtime.voice().isListening();
        recordButton.setText(capture == RecordingController.State.STOPPING
                ? deps.activity.getString(R.string.finishing)
                : voiceListening ? deps.activity.getString(R.string.voice_listening)
                : recordingActive() ? deps.activity.getString(R.string.stop_recording)
                : deps.activity.getString(R.string.voice_input));
        if (voiceMic || voiceListening) {
            String description = (voiceUiStatus != null && !voiceUiStatus.isEmpty())
                    ? voiceUiStatus
                    : (voiceListening ? deps.activity.getString(R.string.voice_listening)
                            : deps.activity.getString(R.string.voice_record_talkback));
            if (voiceListening && !description.toLowerCase(Locale.ROOT).contains("listen")) {
                description = deps.activity.getString(R.string.voice_listening);
            }
            recordButton.setContentDescription(description);
            if (!description.equals(recordButton.getTag(R.id.voice_record))) {
                recordButton.setTag(R.id.voice_record, description);
                if (voiceListening || (voiceUiStatus != null && !voiceUiStatus.isEmpty())) {
                    recordButton.announceForAccessibility(description);
                }
            }
        } else {
            recordButton.setContentDescription(null);
            recordButton.setTag(R.id.voice_record, null);
        }
        recordButton.setEnabled(capture != RecordingController.State.STOPPING
                && (!busy || recordingActive() || voiceMic));
        PlaybackController.State playback = deps.playbackController.state();
        speakButton.setText(playback == PlaybackController.State.STOPPING
                ? deps.activity.getString(R.string.stopping_speech)
                : (playbackActive() || speaking)
                        ? deps.activity.getString(R.string.stop_speech)
                        : deps.activity.getString(R.string.speak_reply));
        long handle = deps.runtime == null ? 0L : deps.runtime.handle();
        boolean modelReady = handle != 0L && (deps.runtime == null || !deps.runtime.isUnloading());
        boolean canSpeakReply = !deps.lastAssistantReply.get().isBlank()
                && ((modelReady && deps.isSettingsLoaded.getAsBoolean()) || canSpeakWithoutOmniModel());
        speakButton.setEnabled((!busy || playbackActive() || speaking)
                && (playbackActive() || speaking || canSpeakReply));
    }

    void toggleRecording() {
        if (recordingActive()) {
            recordingController.stop();
            return;
        }
        if (deps.isBusy.getAsBoolean() || !deps.isForeground.getAsBoolean()) return;
        if (!ensureSttInputReadyIfSelected()) return;
        if (!((Lfm3PocketApplication) deps.activity.getApplication()).hasMicrophonePermission()) {
            microphoneRequested = true;
            deps.requestAudioPermission.run();
            return;
        }
        voiceSttCancelled.set(false);
        recordingController.start();
    }

    void toggleSpokenReply() {
        String reply = deps.lastAssistantReply.get();
        if (playbackActive() || deps.isSpeaking.getAsBoolean()) {
            deps.runtime.stopSpeech();
            return;
        }
        if (deps.isBusy.getAsBoolean() || !deps.isForeground.getAsBoolean() || reply.isBlank()) return;
        long handle = deps.runtime == null ? 0L : deps.runtime.handle();
        boolean unloading = deps.runtime != null && deps.runtime.isUnloading();
        if ((handle == 0L || unloading) && !canSpeakWithoutOmniModel()) return;
        deps.setBusy.accept(true);
        deps.setSpeaking.accept(true);
        deps.updateControls.run();
        deps.runtime.speak(reply);
    }

    boolean consumeAssistantVoiceSessionIntent(Intent intent) {
        if (intent == null) return false;
        if (intent.getBooleanExtra(MainActivity.EXTRA_ASSISTANT_VOICE_SESSION, false)) {
            pendingAssistantVoiceSession = true;
            intent.removeExtra(MainActivity.EXTRA_ASSISTANT_VOICE_SESSION);
            return true;
        }
        return false;
    }

    void maybeStartPendingAssistantVoiceSession() {
        if (!pendingAssistantVoiceSession || deps.isDestroyed.getAsBoolean()) return;
        if (deps.runtime == null || deps.runtime.isGenerating()) return;
        if (deps.runtime.handle() == 0L) {
            String model = deps.preferences.get().getString("model", "");
            if (!deps.isBusy.getAsBoolean() && (model == null || model.isEmpty())) {
                pendingAssistantVoiceSession = false;
                deps.setChatStatus.accept(deps.activity.getString(R.string.assistant_voice_needs_model));
                deps.updateControls.run();
            }
            return;
        }
        if (!deps.runtime.voice().isEnabled()) {
            pendingAssistantVoiceSession = false;
            deps.setChatStatus.accept(deps.runtime.voice().disabledReason());
            deps.updateControls.run();
            return;
        }
        if (!((Lfm3PocketApplication) deps.activity.getApplication()).hasMicrophonePermission()) {
            microphoneRequested = true;
            deps.requestAudioPermission.run();
            return;
        }
        pendingAssistantVoiceSession = false;
        deps.runtime.voice().beginShadeTalk();
        deps.updateControls.run();
    }

    void onAudioPermissionResult(boolean granted) {
        if (!microphoneRequested) return;
        microphoneRequested = false;
        if (granted && pendingAssistantVoiceSession) {
            maybeStartPendingAssistantVoiceSession();
            return;
        }
        if (granted && deps.isForeground.getAsBoolean()
                && !deps.isDestroyed.getAsBoolean() && !deps.isBusy.getAsBoolean()) {
            if (ensureSttInputReadyIfSelected()) recordingController.start();
        } else {
            deps.setChatStatus.accept("Microphone access is needed for voice input.");
        }
        if (!granted) pendingAssistantVoiceSession = false;
    }

    void onStop() {
        voiceSttCancelled.set(true);
        if (recordingActive()) recordingController.cancel();
    }

    void onDestroy() {
        recordingController.close();
        audioWorker.shutdown();
    }

    private boolean ensureSttInputReadyIfSelected() {
        if (!SpeechInputProviders.usesSttTextPipeline(deps.activity)) return true;
        SttAvailability availability = SpeechInputProviders.current(deps.activity).availability(deps.activity);
        if (availability.available) return true;
        String detail = availability.message == null || availability.message.isEmpty()
                ? deps.activity.getString(R.string.speech_input_cactus_status_missing)
                : availability.message;
        deps.setChatStatus.accept(deps.activity.getString(R.string.speech_input_cactus_unavailable, detail));
        return false;
    }

    private void handleCompletedRecordingAsStt(File wav) {
        if (wav == null || !wav.isFile()) return;
        final File recording = wav;
        voiceSttCancelled.set(false);
        transcribingVoiceInput = true;
        deps.setBusy.accept(true);
        deps.setChatStatus.accept(deps.activity.getString(R.string.speech_input_transcribing));
        deps.updateControls.run();
        audioWorker.execute(() -> {
            SttResult result;
            try {
                result = SpeechInputProviders.current(deps.activity)
                        .transcribe(deps.activity, recording, voiceSttCancelled);
            } catch (Throwable t) {
                result = SttResult.error(t.getMessage() == null ? t.toString() : t.getMessage());
            } finally {
                recording.delete();
            }
            final SttResult stt = result;
            deps.mainHandler.post(() -> {
                transcribingVoiceInput = false;
                if (deps.isDestroyed.getAsBoolean()) return;
                deps.recomputeBusy.run();
                if (stt == null || stt.status == SttResult.Status.CANCELLED || voiceSttCancelled.get()) {
                    deps.setChatStatus.accept("Speech transcription cancelled.");
                    deps.updateControls.run();
                    return;
                }
                if (!stt.isOk()) {
                    if (stt.status == SttResult.Status.EMPTY) {
                        String msg = stt.message == null || stt.message.isEmpty()
                                ? deps.activity.getString(R.string.speech_input_transcript_empty)
                                : stt.message;
                        deps.setChatStatus.accept(msg);
                    } else {
                        String detail = stt.message == null || stt.message.isEmpty() ? "unknown error" : stt.message;
                        deps.setChatStatus.accept(deps.activity.getString(
                                R.string.speech_input_transcript_failed, detail));
                    }
                    deps.updateControls.run();
                    return;
                }
                deps.updateControls.run();
                long handle = deps.runtime == null ? 0L : deps.runtime.handle();
                boolean unloading = deps.runtime != null && deps.runtime.isUnloading();
                if (handle == 0L || unloading || !deps.isSettingsLoaded.getAsBoolean() || deps.isBusy.getAsBoolean()) {
                    ChatPanel panel = deps.chatPanel.get();
                    if (panel != null) panel.setDraft(stt.text);
                    deps.setChatStatus.accept(deps.activity.getString(R.string.speech_input_transcribed_draft));
                    return;
                }
                // Route recognized text into the existing text/inference pipeline (same as typed text).
                deps.sendText.accept(stt.text);
            });
        });
    }

    private boolean canSpeakWithoutOmniModel() {
        return new RuntimePreferences(deps.activity).tinyVocaEnglishTtsEnabled();
    }
}
