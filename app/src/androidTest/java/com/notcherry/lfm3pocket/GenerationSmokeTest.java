package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class GenerationSmokeTest {
    @Test public void nativeGenerationStreamsStopsAndCanRestart() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String modelPath = InstrumentationRegistry.getArguments().getString("modelPath");
        File modelFile = modelPath == null || modelPath.trim().isEmpty()
                ? copyPackagedSmokeModel(context)
                : new File(modelPath);
        assertTrue("Smoke GGUF must exist (run provision-smoke-model.sh before building androidTest)",
                modelFile.isFile());

        long handle = NativeBridge.loadModel(modelFile.getAbsolutePath(), null);
        assertTrue("Native engine failed to load smoke GGUF", handle != 0L);
        try {
            CountDownLatch firstToken = new CountDownLatch(1);
            CountDownLatch firstFinished = new CountDownLatch(1);
            StringBuilder streamed = new StringBuilder();
            AtomicReference<String> error = new AtomicReference<>();
            NativeBridge.resetStop();
            Thread generation = new Thread(() -> {
                try {
                    error.set(NativeBridge.generate(handle,
                            "Once upon a time, a small robot", null, 512, piece -> {
                                streamed.append(piece);
                                firstToken.countDown();
                            }));
                } catch (Throwable t) {
                    error.set(t.toString());
                } finally {
                    firstFinished.countDown();
                }
            }, "lfm3-smoke-generation");
            generation.start();

            assertTrue("No token streamed within 30 seconds", firstToken.await(30, TimeUnit.SECONDS));
            NativeBridge.stop();
            assertTrue("Stop did not finish generation", firstFinished.await(30, TimeUnit.SECONDS));
            assertTrue("Generation did not stream text", streamed.length() > 0);
            assertNull("Generation reported an error: " + error.get(), error.get());

            NativeBridge.resetStop();
            CountDownLatch retryFinished = new CountDownLatch(1);
            StringBuilder retry = new StringBuilder();
            AtomicReference<String> retryError = new AtomicReference<>();
            Thread secondGeneration = new Thread(() -> {
                try {
                    retryError.set(NativeBridge.generate(handle,
                            "A short story:", null, 12, retry::append));
                } catch (Throwable t) {
                    retryError.set(t.toString());
                } finally {
                    retryFinished.countDown();
                }
            }, "lfm3-smoke-retry");
            secondGeneration.start();
            assertTrue("Generation after Stop did not finish", retryFinished.await(30, TimeUnit.SECONDS));
            assertNull("Retry reported an error: " + retryError.get(), retryError.get());
            assertTrue("Generation after Stop produced no output: " + retryError.get(), retry.length() > 0);
        } finally {
            NativeBridge.stop();
            NativeBridge.unloadModel(handle);
        }
    }

    /**
     * The fixture is packaged into the instrumentation APK by the host build.
     * The Android emulator never fetches GGUF weights over the network.
     */
    static File copyPackagedSmokeModel(Context context) throws Exception {
        File destination = new File(context.getCacheDir(), "stories15M-q4_0.gguf");
        try (InputStream input = InstrumentationRegistry.getInstrumentation()
                .getContext().getAssets().open("stories15M-q4_0.gguf");
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        }
        assertTrue("Packaged GGUF has incorrect size", destination.length() == 19077344L);
        return destination;
    }
}
