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
    interface Callback {
        void onStarted();
        void onFinished(String status);
        void onError(String message);
    }

    private final Context context;
    private final Handler main;
    private TextToSpeech engine;
    private final AtomicBoolean ready = new AtomicBoolean();

    LabeledSystemTts(Context context) {
        this.context = context.getApplicationContext();
        this.main = new Handler(Looper.getMainLooper());
        main.post(() -> engine = new TextToSpeech(this.context, status -> {
            ready.set(status == TextToSpeech.SUCCESS);
        }));
    }

    void speak(String text, String languageTag, Callback callback) {
        if (text == null || text.isBlank()) {
            callback.onError("Nothing to speak");
            return;
        }
        Runnable work = () -> {
            if (!ready.get() || engine == null) {
                callback.onError("System voice (labelled TTS) is not ready");
                return;
            }
            Locale locale = languageTag != null && languageTag.startsWith("pl")
                    ? new Locale("pl") : Locale.US;
            int lang = engine.setLanguage(locale);
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                if (!locale.equals(Locale.US)) engine.setLanguage(Locale.US);
            }
            engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) { callback.onStarted(); }
                @Override public void onDone(String utteranceId) {
                    callback.onFinished("System voice (labelled TTS)");
                }
                @Override public void onError(String utteranceId) {
                    callback.onError("System voice playback failed");
                }
            });
            int code = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hanami-voice-action");
            if (code != TextToSpeech.SUCCESS) callback.onError("System voice could not start");
        };
        if (Looper.myLooper() == main.getLooper()) work.run();
        else main.post(work);
    }

    void stopSpeaking() {
        main.post(() -> {
            if (engine != null) engine.stop();
        });
    }

    void shutdown() {
        main.post(() -> {
            if (engine != null) {
                engine.stop();
                engine.shutdown();
                engine = null;
                ready.set(false);
            }
        });
    }
}