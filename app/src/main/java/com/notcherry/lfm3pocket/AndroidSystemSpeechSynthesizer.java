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

/** System TTS fallback for English when tiny-voca is unavailable. */
public final class AndroidSystemSpeechSynthesizer {
    private static final String UTTERANCE_ID = "hanami-spoken-reply";

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private volatile TextToSpeech engine;
    private volatile boolean speaking;
    private volatile boolean stopRequested;

    public AndroidSystemSpeechSynthesizer(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isSpeaking() {
        return speaking;
    }

    public interface Callback {
        void onDone();
        void onError(String message);
    }

    public void stop() {
        stopRequested = true;
        main.post(() -> {
            TextToSpeech tts = engine;
            if (tts != null) {
                try { tts.stop(); } catch (IllegalStateException ignored) { }
            }
            speaking = false;
        });
    }

    private void shutdownEngineOnMain() {
        main.post(() -> {
            synchronized (lock) {
                TextToSpeech tts = engine;
                if (tts != null) {
                    try { tts.stop(); } catch (IllegalStateException ignored) { }
                    try { tts.shutdown(); } catch (Exception ignored) { }
                }
                engine = null;
            }
            speaking = false;
        });
    }

    public void speak(Locale locale, String text, Callback callback) {
        if (text == null || text.isBlank()) {
            if (callback != null) callback.onError("Nothing to speak");
            return;
        }
        stopRequested = false;
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<String> initError = new AtomicReference<>();
        main.post(() -> {
            synchronized (lock) {
                if (engine != null) {
                    try { engine.shutdown(); } catch (Exception ignored) { }
                }
                engine = new TextToSpeech(context, status -> {
                    if (status != TextToSpeech.SUCCESS) {
                        initError.set("TextToSpeech init failed");
                        ready.countDown();
                        return;
                    }
                    TextToSpeech tts = engine;
                    if (tts == null) {
                        initError.set("TextToSpeech unavailable");
                        ready.countDown();
                        return;
                    }
                    Locale chosen = locale == null ? Locale.US : locale;
                    int available = tts.isLanguageAvailable(chosen);
                    if (available < TextToSpeech.LANG_AVAILABLE) {
                        chosen = Locale.US;
                        available = tts.isLanguageAvailable(chosen);
                    }
                    if (available < TextToSpeech.LANG_AVAILABLE) {
                        initError.set("No English system voice");
                        ready.countDown();
                        return;
                    }
                    tts.setLanguage(chosen);
                    ready.countDown();
                });
            }
        });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!ready.await(250, TimeUnit.MILLISECONDS)) {
                if (stopRequested) {
                    shutdownEngineOnMain();
                    if (callback != null) callback.onError("Spoken response cancelled.");
                    return;
                }
                if (System.nanoTime() >= deadline) {
                    shutdownEngineOnMain();
                    if (callback != null) callback.onError("TextToSpeech timed out");
                    return;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            shutdownEngineOnMain();
            if (callback != null) callback.onError("Interrupted");
            return;
        }
        if (stopRequested) {
            shutdownEngineOnMain();
            if (callback != null) callback.onError("Spoken response cancelled.");
            return;
        }
        if (initError.get() != null) {
            shutdownEngineOnMain();
            if (callback != null) callback.onError(initError.get());
            return;
        }
        TextToSpeech tts = engine;
        if (tts == null) {
            if (callback != null) callback.onError("TextToSpeech unavailable");
            return;
        }
        if (stopRequested) {
            shutdownEngineOnMain();
            if (callback != null) callback.onError("Spoken response cancelled.");
            return;
        }
        speaking = true;
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
        int queued = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID);
        if (queued == TextToSpeech.ERROR) {
            speaking = false;
            shutdownEngineOnMain();
            if (callback != null) callback.onError("Could not queue system speech");
            return;
        }
        new Thread(() -> {
            try {
                long seconds = Math.max(15, text.length() / 8);
                for (long waited = 0; waited < seconds * 1000L && !done.await(250, TimeUnit.MILLISECONDS); waited += 250) {
                    if (stopRequested) {
                        error.set("Spoken response cancelled.");
                        break;
                    }
                }
                if (!stopRequested && done.getCount() > 0) {
                    error.set("System TTS timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                error.set("Interrupted");
            } finally {
                speaking = false;
                shutdownEngineOnMain();
            }
            if (stopRequested) {
                if (callback != null) callback.onError("Spoken response cancelled.");
            } else if (error.get() != null) {
                if (callback != null) callback.onError(error.get());
            } else if (callback != null) callback.onDone();
        }, "hanami-system-tts").start();
    }
}