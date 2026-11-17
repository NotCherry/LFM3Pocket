package com.notcherry.lfm3pocket;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** System TTS fallback for English when tiny-voca is unavailable. */
public final class AndroidSystemSpeechSynthesizer {
    private static final String UTTERANCE_ID = "hanami-spoken-reply";

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SystemSpeechEngineSlot<TextToSpeech> slot =
            new SystemSpeechEngineSlot<>(main::post, new SystemSpeechEngineSlot.EngineOps<TextToSpeech>() {
                @Override public void stopEngine(TextToSpeech engine) {
                    if (engine == null) return;
                    try { engine.stop(); } catch (IllegalStateException ignored) { }
                }
                @Override public void shutdownEngine(TextToSpeech engine) {
                    if (engine == null) return;
                    try { engine.shutdown(); } catch (Exception ignored) { }
                }
            });
    private final AtomicLong sessionSeq = new AtomicLong();

    private volatile boolean speaking;
    private volatile boolean sessionActive;
    private volatile boolean stopRequested;

    public AndroidSystemSpeechSynthesizer(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isSpeaking() {
        return speaking || sessionActive;
    }

    public interface Callback {
        void onDone();
        void onError(String message);
    }

    public void stop() {
        stopRequested = true;
        main.post(() -> {
            TextToSpeech tts = slot.currentEngine();
            if (tts != null) {
                try { tts.stop(); } catch (IllegalStateException ignored) { }
            }
        });
    }

    private void clearSpeakingForSession(long sessionId) {
        if (slot.activeSessionId() == sessionId) {
            speaking = false;
            sessionActive = false;
        }
    }

    private void shutdownSession(TextToSpeech victim, long sessionId) {
        slot.shutdownOnMain(victim, sessionId, () -> clearSpeakingForSession(sessionId));
    }

    public void speak(Locale locale, String text, Callback callback) {
        if (text == null || text.isBlank()) {
            if (callback != null) callback.onError("Nothing to speak");
            return;
        }
        stopRequested = false;
        long sessionId = sessionSeq.incrementAndGet();
        sessionActive = true;
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<String> initError = new AtomicReference<>();
        AtomicReference<TextToSpeech> sessionEngine = new AtomicReference<>();
        main.post(() -> {
            final TextToSpeech[] holder = new TextToSpeech[1];
            TextToSpeech created = new TextToSpeech(context, status -> {
                TextToSpeech tts = holder[0];
                if (tts == null) {
                    initError.set("TextToSpeech unavailable");
                    ready.countDown();
                    return;
                }
                if (status != TextToSpeech.SUCCESS) {
                    initError.set("TextToSpeech init failed");
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
            holder[0] = created;
            sessionEngine.set(slot.prepareNewEngine(sessionId, created));
        });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!ready.await(250, TimeUnit.MILLISECONDS)) {
                if (stopRequested) {
                    shutdownSession(sessionEngine.get(), sessionId);
                    if (callback != null) callback.onError("Spoken response cancelled.");
                    return;
                }
                if (System.nanoTime() >= deadline) {
                    shutdownSession(sessionEngine.get(), sessionId);
                    if (callback != null) callback.onError("TextToSpeech timed out");
                    return;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            shutdownSession(sessionEngine.get(), sessionId);
            if (callback != null) callback.onError("Interrupted");
            return;
        }
        if (stopRequested) {
            shutdownSession(sessionEngine.get(), sessionId);
            if (callback != null) callback.onError("Spoken response cancelled.");
            return;
        }
        if (initError.get() != null) {
            shutdownSession(sessionEngine.get(), sessionId);
            if (callback != null) callback.onError(initError.get());
            return;
        }
        TextToSpeech tts = sessionEngine.get();
        if (tts == null) {
            clearSpeakingForSession(sessionId);
            if (callback != null) callback.onError("TextToSpeech unavailable");
            return;
        }
        if (stopRequested) {
            shutdownSession(tts, sessionId);
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
            shutdownSession(tts, sessionId);
            if (callback != null) callback.onError("Could not queue system speech");
            return;
        }
        TextToSpeech sessionTts = tts;
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
                shutdownSession(sessionTts, sessionId);
            }
            if (stopRequested) {
                if (callback != null) callback.onError("Spoken response cancelled.");
            } else if (error.get() != null) {
                if (callback != null) callback.onError(error.get());
            } else if (callback != null) callback.onDone();
        }, "hanami-system-tts").start();
    }
}