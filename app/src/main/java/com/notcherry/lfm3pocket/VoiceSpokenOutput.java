package com.notcherry.lfm3pocket;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Spoken replies for voice actions: prefer Omni {@code generateSpeech}, otherwise labelled system TTS.
 * Confirm read-backs use the exact {@link ToolCoordinator#describe} bytes (no paraphrase).
 */
public final class VoiceSpokenOutput {
    public interface Host {
        long modelHandle();
        InferenceEngine engine();
        PlaybackController playback();
        Executor worker();
        Handler main();
        android.content.Context app();
        void onSpeechUi(boolean active, String status);
        void onPlaybackPolicy(boolean playing);
        void policySpeechStarted();
    }

    private final Host host;
    private final LabeledSystemTts systemTts;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile Runnable pendingComplete;
    private volatile String lastPath = "omni";

    public VoiceSpokenOutput(Host host) {
        this.host = host;
        this.systemTts = new LabeledSystemTts(host.app());
    }

    public String lastSpokenPath() { return lastPath; }

    public boolean isBusy() { return busy.get(); }

    public void stop() {
        host.playback().stop();
        systemTts.stopSpeaking();
        pendingComplete = null;
        busy.set(false);
        host.onSpeechUi(false, "");
    }

  /** Called when Omni PCM playback reaches idle — runs any deferred {@code onComplete}. */
    void onPlaybackIdle() {
        Runnable done = pendingComplete;
        pendingComplete = null;
        busy.set(false);
        if (done != null) host.main().post(done);
    }

    public void speakExact(String exactText, Locale locale, Runnable onComplete) {
        if (exactText == null || exactText.isBlank()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            if (onComplete != null) onComplete.run();
            return;
        }
        String language = locale == null ? "en" : locale.getLanguage();
        String voice = "female";
        String spoken = SpeechPrompt.spokenText(exactText);
        host.policySpeechStarted();
        host.onSpeechUi(true, "Generating spoken response locally…");
        host.worker().execute(() -> generateOmni(spoken, language, voice, onComplete));
    }

    private void generateOmni(String spoken, String language, String voice, Runnable onComplete) {
        java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
        int[] format = new int[] { 24_000, 1, 16 };
        String error;
        try {
            host.engine().resetStop();
            error = host.engine().generateSpeech(host.modelHandle(), SpeechPrompt.tts(spoken, language, voice),
                    new SpeechOptions(language, voice, SpeechOptions.framesForText(spoken)),
                    new InferenceEngine.AudioSink() {
                        @Override public void onAudioFormat(int sampleRateHz, int channelCount, int bitsPerSample) {
                            format[0] = sampleRateHz;
                            format[1] = channelCount;
                            format[2] = bitsPerSample;
                        }
                        @Override public void onAudioPcm(byte[] interleavedLittleEndianPcm) {
                            try {
                                pcm.write(interleavedLittleEndianPcm);
                            } catch (java.io.IOException e) {
                                throw new IllegalStateException(e);
                            }
                        }
                    });
        } catch (Throwable thrown) {
            error = thrown.toString();
        }
        byte[] audio = pcm.toByteArray();
        String fail = error;
        host.main().post(() -> finishOmni(fail, audio, format, spoken, language, onComplete));
    }

    private void finishOmni(String fail, byte[] audio, int[] format, String spoken, String language,
                            Runnable onComplete) {
        if (fail == null && audio.length > 0) {
            lastPath = "omni";
            if (host.playback().play(audio, format[0], format[1], format[2])) {
                host.onPlaybackPolicy(true);
                host.onSpeechUi(false, "Playing reply");
                pendingComplete = onComplete;
                return;
            }
            fail = "Could not start Omni speech playback";
        }
        fallbackSystem(spoken, language, fail, onComplete);
    }

    private void fallbackSystem(String spoken, String language, String omniFailure, Runnable onComplete) {
        lastPath = "system_tts";
        String status = omniFailure == null
                ? "System voice (labelled TTS)"
                : "Omni speech unavailable — System voice (labelled TTS): " + omniFailure;
        systemTts.speak(spoken, language, new LabeledSystemTts.Callback() {
            @Override public void onStarted() { host.onSpeechUi(true, status); }
            @Override public void onFinished(String path) {
                busy.set(false);
                host.onSpeechUi(false, path);
                if (onComplete != null) onComplete.run();
            }
            @Override public void onError(String message) {
                busy.set(false);
                host.onSpeechUi(false, message);
                if (onComplete != null) onComplete.run();
            }
        });
    }

    public static String confirmSpokenPrompt(String describe, Locale locale) {
        boolean pl = locale != null && "pl".equals(locale.getLanguage());
        String tail = pl ? " Tak czy nie?" : " Yes or no?";
        return describe + tail;
    }
}