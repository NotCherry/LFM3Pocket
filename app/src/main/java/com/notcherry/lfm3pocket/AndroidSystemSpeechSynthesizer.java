package com.notcherry.lfm3pocket;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** System TTS fallback for Polish or when tiny-voca assets are missing. */
public final class AndroidSystemSpeechSynthesizer {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    public AndroidSystemSpeechSynthesizer(Context context) {
        this.context = context.getApplicationContext();
    }

    public interface Callback {
        void onDone();
        void onError(String message);
    }

    public void speak(String text, Callback callback) {
        if (text == null || text.isBlank()) {
            if (callback != null) callback.onError("Nothing to speak");
            return;
        }
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<TextToSpeech> engine = new AtomicReference<>();
        AtomicReference<String> initError = new AtomicReference<>();
        main.post(() -> engine.set(new TextToSpeech(context, status -> {
            if (status != TextToSpeech.SUCCESS) {
                initError.set("TextToSpeech init failed");
                ready.countDown();
                return;
            }
            TextToSpeech tts = engine.get();
            if (tts == null) {
                initError.set("TextToSpeech unavailable");
                ready.countDown();
                return;
            }
            Locale polish = new Locale("pl", "PL");
            Locale english = Locale.US;
            int pl = tts.isLanguageAvailable(polish);
            int en = tts.isLanguageAvailable(english);
            if (pl >= TextToSpeech.LANG_AVAILABLE) tts.setLanguage(polish);
            else if (en >= TextToSpeech.LANG_AVAILABLE) tts.setLanguage(english);
            ready.countDown();
        })));
        try {
            if (!ready.await(5, TimeUnit.SECONDS)) {
                if (callback != null) callback.onError("TextToSpeech timed out");
                return;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (callback != null) callback.onError("Interrupted");
            return;
        }
        if (initError.get() != null) {
            if (callback != null) callback.onError(initError.get());
            return;
        }
        TextToSpeech tts = engine.get();
        if (tts == null) {
            if (callback != null) callback.onError("TextToSpeech unavailable");
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) { }
            @Override public void onDone(String utteranceId) { done.countDown(); }
            @Override public void onError(String utteranceId) {
                error.set("System TTS error");
                done.countDown();
            }
        });
        int queued = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hanami-spoken-reply");
        if (queued == TextToSpeech.ERROR) {
            tts.shutdown();
            if (callback != null) callback.onError("Could not queue system speech");
            return;
        }
        main.post(() -> { /* keep engine alive until finished on binder thread */ });
        new Thread(() -> {
            try {
                if (!done.await(Math.max(15, text.length() / 8), TimeUnit.SECONDS)) {
                    error.set("System TTS timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                error.set("Interrupted");
            } finally {
                main.post(() -> {
                    tts.stop();
                    tts.shutdown();
                });
            }
            if (error.get() != null) {
                if (callback != null) callback.onError(error.get());
            } else if (callback != null) callback.onDone();
        }, "hanami-system-tts").start();
    }
}