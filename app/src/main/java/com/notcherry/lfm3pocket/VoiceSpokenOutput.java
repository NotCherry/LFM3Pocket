package com.notcherry.lfm3pocket;

import android.os.Handler;

import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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

    private static final class QueuedSpeak {
        final String text;
        final Locale locale;
        final Runnable onComplete;
        final String uiStatus;

        QueuedSpeak(String text, Locale locale, Runnable onComplete) {
            this(text, locale, onComplete, null);
        }

        QueuedSpeak(String text, Locale locale, Runnable onComplete, String uiStatus) {
            this.text = text;
            this.locale = locale;
            this.onComplete = onComplete;
            this.uiStatus = uiStatus;
        }
    }

    private final Host host;
    private LabeledSystemTts systemTts;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicLong speechGeneration = new AtomicLong();
    private final ConcurrentLinkedQueue<QueuedSpeak> speakQueue = new ConcurrentLinkedQueue<>();
    private volatile Runnable pendingComplete;
    private volatile long pendingCompleteGeneration;
    private volatile long activeUtteranceGeneration;
    private volatile String lastPath = "omni";

    public VoiceSpokenOutput(Host host) {
        this.host = host;
    }

    public String lastSpokenPath() { return lastPath; }

    public boolean isBusy() { return busy.get(); }

    long currentSpeechGeneration() { return speechGeneration.get(); }

    /** Drop deferred {@code onComplete} hooks without tearing down in-flight PCM/TTS. */
    void invalidateDeferredCompletions() {
        speechGeneration.incrementAndGet();
        speakQueue.clear();
        pendingComplete = null;
    }

    public void stop() {
        speechGeneration.incrementAndGet();
        speakQueue.clear();
        pendingComplete = null;
        host.playback().stop();
        LabeledSystemTts tts = systemTts;
        if (tts != null) tts.stopSpeaking();
        busy.set(false);
        host.onSpeechUi(false, "");
    }

  /** Called when Omni PCM playback reaches idle — runs any deferred {@code onComplete}. */
    void onPlaybackIdle() {
        Runnable done = pendingComplete;
        long gen = pendingCompleteGeneration;
        pendingComplete = null;
        busy.set(false);
        if (done != null && gen == speechGeneration.get()) done.run();
        drainQueue();
    }

    public void speakExact(String exactText, Locale locale, Runnable onComplete) {
        speakExact(exactText, locale, onComplete, null);
    }

    public void speakExact(String exactText, Locale locale, Runnable onComplete, String uiStatus) {
        if (exactText == null || exactText.isBlank()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            speakQueue.offer(new QueuedSpeak(exactText, locale, onComplete, uiStatus));
            return;
        }
        beginSpeak(exactText, locale, onComplete, uiStatus);
    }

    private void beginSpeak(String exactText, Locale locale, Runnable onComplete) {
        beginSpeak(exactText, locale, onComplete, null);
    }

    private void beginSpeak(String exactText, Locale locale, Runnable onComplete, String uiStatus) {
        activeUtteranceGeneration = speechGeneration.get();
        String language = locale == null ? "en" : locale.getLanguage();
        String voice = "female";
        String spoken = SpeechPrompt.spokenText(exactText);
        host.policySpeechStarted();
        host.onSpeechUi(true, uiStatus != null ? uiStatus : "Generating spoken response locally…");
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
        postToMain(() -> finishOmni(fail, audio, format, spoken, language, onComplete));
    }

    private void postToMain(Runnable runnable) {
        Handler main = host.main();
        if (main != null) main.post(runnable);
        else runnable.run();
    }

    private void finishOmni(String fail, byte[] audio, int[] format, String spoken, String language,
                            Runnable onComplete) {
        if (activeUtteranceGeneration != speechGeneration.get()) {
            utteranceFailedCleanup();
            return;
        }
        if (fail == null && audio.length > 0) {
            lastPath = "omni";
            if (host.playback().play(audio, format[0], format[1], format[2])) {
                host.onPlaybackPolicy(true);
                host.onSpeechUi(false, "Playing reply");
                if (onComplete != null) {
                    pendingComplete = onComplete;
                    pendingCompleteGeneration = activeUtteranceGeneration;
                } else {
                    pendingComplete = null;
                }
                return;
            }
            fail = "Could not start Omni speech playback";
        }
        fallbackSystem(spoken, language, fail, onComplete);
    }

    private void fallbackSystem(String spoken, String language, String omniFailure, Runnable onComplete) {
        if (activeUtteranceGeneration != speechGeneration.get()) {
            utteranceFailedCleanup();
            return;
        }
        lastPath = "system_tts";
        LabeledSystemTts tts = tts();
        if (tts == null) {
            finishUtterance(onComplete);
            return;
        }
        String status = omniFailure == null
                ? "System voice (labelled TTS)"
                : "Omni speech unavailable — System voice (labelled TTS): " + omniFailure;
        final long utteranceGeneration = activeUtteranceGeneration;
        tts.speak(spoken, language, new LabeledSystemTts.Callback() {
            @Override public void onStarted() { host.onSpeechUi(true, status); }
            @Override public void onFinished(String path) {
                host.onSpeechUi(false, path);
                finishUtteranceFromBackground(onComplete, utteranceGeneration);
            }
            @Override public void onError(String message) {
                host.onSpeechUi(false, message);
                finishUtteranceFromBackground(onComplete, utteranceGeneration);
            }
        });
    }

    private LabeledSystemTts tts() {
        if (systemTts == null && host.app() != null) systemTts = new LabeledSystemTts(host.app());
        return systemTts;
    }

    private void finishUtterance(Runnable onComplete) {
        finishUtteranceFromBackground(onComplete, activeUtteranceGeneration);
    }

    private void finishUtteranceFromBackground(Runnable onComplete, long utteranceGeneration) {
        busy.set(false);
        Runnable complete = onComplete;
        if (complete != null && utteranceGeneration == speechGeneration.get()) {
            final long gen = utteranceGeneration;
            postToMain(() -> {
                if (gen == speechGeneration.get()) complete.run();
                drainQueue();
            });
            return;
        }
        drainQueue();
    }

    private void utteranceFailedCleanup() {
        pendingComplete = null;
        busy.set(false);
        drainQueue();
    }

    private void drainQueue() {
        while (!busy.get()) {
            QueuedSpeak next = speakQueue.peek();
            if (next == null) return;
            if (!busy.compareAndSet(false, true)) continue;
            QueuedSpeak claimed = speakQueue.poll();
            if (claimed == null) {
                busy.set(false);
                return;
            }
            beginSpeak(claimed.text, claimed.locale, claimed.onComplete, claimed.uiStatus);
            return;
        }
    }

    public static String confirmSpokenPrompt(String describe, Locale locale) {
        boolean pl = locale != null && "pl".equals(locale.getLanguage());
        String tail = pl ? " Tak czy nie?" : " Yes or no?";
        return describe + tail;
    }
}