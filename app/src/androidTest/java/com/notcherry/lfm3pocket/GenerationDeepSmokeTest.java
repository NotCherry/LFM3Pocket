package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Deep-tier JNI coverage moved out of the minimal PR smoke proof. */
@RunWith(AndroidJUnit4.class)
public final class GenerationDeepSmokeTest {
    @Test public void promptLookupCachedTurnAndSpeechRejectionRemainCorrect() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String modelPath = InstrumentationRegistry.getArguments().getString("modelPath");
        File modelFile = modelPath == null || modelPath.trim().isEmpty()
                ? GenerationSmokeTest.copyPackagedSmokeModel(context)
                : new File(modelPath);
        assertTrue("Smoke GGUF must exist (run provision-smoke-model.sh before building androidTest)",
                modelFile.isFile());

        long handle = NativeBridge.loadModel(modelFile.getAbsolutePath(), null);
        assertTrue("Native engine failed to load smoke GGUF", handle != 0L);
        try {
            final String[] speechError = new String[1];
            final int[] speechFormatCalls = new int[1];
            NativeBridge.resetStop();
            speechError[0] = NativeBridge.generateSpeech(handle,
                    SpeechPrompt.tts("Hello from the smoke fixture.", "en", "female"),
                    SpeechOptions.DEFAULT,
                    new NativeBridge.AudioSink() {
                        @Override public void onAudioFormat(int sampleRateHz, int channelCount, int bitsPerSample) {
                            speechFormatCalls[0]++;
                        }
                        @Override public void onAudioPcm(byte[] interleavedLittleEndianPcm) {}
                        @Override public void onAudioComplete() {}
                    });
            assertNotNull("Stories15M smoke model must reject speech generation clearly", speechError[0]);
            assertTrue("Expected a speech-unavailable error, got: " + speechError[0],
                    speechError[0].toLowerCase().contains("speech"));
            assertTrue("Unsupported speech generation must not stream audio", speechFormatCalls[0] == 0);

            NativeBridge.resetStop();
            StringBuilder lookupStream = new StringBuilder();
            AtomicReference<String> lookupError = new AtomicReference<>();
            CountDownLatch lookupDone = new CountDownLatch(1);
            InferenceSettings lookupOn = new InferenceSettings(512, 1, 32, true);
            Thread lookupGen = new Thread(() -> {
                try {
                    lookupError.set(NativeBridge.generateWithSettings(handle,
                            "Once upon a time", null, lookupOn, lookupStream::append));
                } catch (Throwable t) {
                    lookupError.set(t.toString());
                } finally {
                    lookupDone.countDown();
                }
            }, "lfm3-prompt-lookup");
            lookupGen.start();
            assertTrue("Prompt lookup generation did not finish", lookupDone.await(45, TimeUnit.SECONDS));
            assertNull("Prompt lookup error: " + lookupError.get(), lookupError.get());
            assertTrue("Prompt lookup produced no text", lookupStream.length() > 0);

            NativeBridge.resetStop();
            StringBuilder cached = new StringBuilder();
            AtomicReference<String> cacheError = new AtomicReference<>();
            CountDownLatch cacheDone = new CountDownLatch(1);
            Thread cacheGen = new Thread(() -> {
                try {
                    cacheError.set(NativeBridge.generateWithSettings(handle,
                            "Once upon a time, a short", null, lookupOn, cached::append));
                } catch (Throwable t) {
                    cacheError.set(t.toString());
                } finally {
                    cacheDone.countDown();
                }
            }, "lfm3-prompt-lookup-cache");
            cacheGen.start();
            assertTrue("Second prompt-lookup turn did not finish", cacheDone.await(45, TimeUnit.SECONDS));
            assertNull("Cached prompt lookup error: " + cacheError.get(), cacheError.get());
            assertTrue("Cached prompt lookup produced no text", cached.length() > 0);
        } finally {
            NativeBridge.stop();
            NativeBridge.unloadModel(handle);
        }
    }
}
