package com.notcherry.lfm3pocket;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Labelled Android {@link TextToSpeech} fallback when Omni has no gen_audio. */
final class LabeledSystemTts {
    private static final long INIT_TIMEOUT_MS = 5_000L;

    interface Callback {
        void onStarted();
        void onFinished(String status);
        void onError(String message);
    }

    private enum InitState { INITIALIZING, READY, FAILED, SHUTDOWN }

    private static final class PendingSpeak {
        final String text;
        final String languageTag;
        final Callback callback;
        Runnable timeout;

        PendingSpeak(String text, String languageTag, Callback callback) {
            this.text = text;
            this.languageTag = languageTag;
            this.callback = callback;
        }
    }

    private final Context context;
    private final Handler main;
    private TextToSpeech engine;
    private InitState initState = InitState.INITIALIZING;
    private PendingSpeak pending;

    LabeledSystemTts(Context context) {
        this.context = context.getApplicationContext();
        this.main = new Handler(Looper.getMainLooper());
        Runnable init = () -> {
            if (initState == InitState.SHUTDOWN) return;
            engine = new TextToSpeech(this.context, status -> main.post(() -> onEngineInit(status)));
        };
        if (Looper.myLooper() == main.getLooper()) init.run();
        else main.post(init);
    }

    private void onEngineInit(int status) {
        if (initState == InitState.SHUTDOWN) {
            if (engine != null) {
                try { engine.shutdown(); } catch (Exception ignored) {}
                engine = null;
            }
            return;
        }
        PendingSpeak queued = takePending();
        if (status == TextToSpeech.SUCCESS && engine != null) {
            initState = InitState.READY;
            if (queued != null) {
                startSpeakingNow(queued.text, queued.languageTag, queued.callback);
            }
        } else {
            initState = InitState.FAILED;
            if (queued != null) {
                queued.callback.onError("System voice (labelled TTS) is not ready");
            }
        }
    }

    void speak(String text, String languageTag, Callback callback) {
        if (text == null || text.isBlank()) {
            callback.onError("Nothing to speak");
            return;
        }
        Runnable work = () -> {
            if (initState == InitState.READY && engine != null) {
                startSpeakingNow(text, languageTag, callback);
                return;
            }
            if (initState == InitState.INITIALIZING) {
                takePending();
                PendingSpeak queued = new PendingSpeak(text, languageTag, callback);
                queued.timeout = () -> {
                    if (pending == queued) {
                        pending = null;
                        callback.onError("System voice (labelled TTS) timed out initializing");
                    }
                };
                pending = queued;
                main.postDelayed(queued.timeout, INIT_TIMEOUT_MS);
                return;
            }
            callback.onError("System voice (labelled TTS) is not ready");
        };
        if (Looper.myLooper() == main.getLooper()) work.run();
        else main.post(work);
    }

    private void startSpeakingNow(String text, String languageTag, Callback callback) {
        if (engine == null) {
            callback.onError("System voice (labelled TTS) is not ready");
            return;
        }
        Locale locale = languageTag != null && languageTag.startsWith("pl")
                ? new Locale("pl") : Locale.US;
        int lang = engine.setLanguage(locale);
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (!locale.equals(Locale.US)) engine.setLanguage(Locale.US);
        }
        final AtomicBoolean completed = new AtomicBoolean();
        engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) { callback.onStarted(); }
            @Override public void onDone(String utteranceId) {
                if (completed.compareAndSet(false, true)) {
                    callback.onFinished("System voice (labelled TTS)");
                }
            }
            @Override public void onError(String utteranceId) {
                if (completed.compareAndSet(false, true)) {
                    callback.onError("System voice playback failed");
                }
            }
            @Override public void onStop(String utteranceId, boolean interrupted) {
                if (completed.compareAndSet(false, true)) {
                    callback.onFinished("System voice stopped");
                }
            }
        });
        int code = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hanami-voice-action");
        if (code != TextToSpeech.SUCCESS && completed.compareAndSet(false, true)) {
            callback.onError("System voice could not start");
        }
    }

    private PendingSpeak takePending() {
        PendingSpeak queued = pending;
        pending = null;
        if (queued != null && queued.timeout != null) {
            main.removeCallbacks(queued.timeout);
        }
        return queued;
    }

    void stopSpeaking() {
        main.post(() -> {
            takePending();
            if (engine != null) {
                try { engine.stop(); } catch (Exception ignored) {}
            }
        });
    }

    void shutdown() {
        main.post(() -> {
            initState = InitState.SHUTDOWN;
            takePending();
            if (engine != null) {
                try { engine.stop(); } catch (Exception ignored) {}
                try { engine.shutdown(); } catch (Exception ignored) {}
                engine = null;
            }
        });
    }
}