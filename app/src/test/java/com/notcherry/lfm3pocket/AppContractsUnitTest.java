package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/** Pure JVM regression tests: no Android emulator, JNI or GGUF required. */
public final class AppContractsUnitTest {
    @Test public void androidActionResultRetainsExplicitDenial() {
        AndroidActionExecutor executor = (name, validatedArguments) ->
                new AndroidActionExecutor.ActionResult(false, "DENIED", "Not approved");
        AndroidActionExecutor.ActionResult result =
                executor.execute("flashlight", Collections.singletonMap("on", "true"));
        assertFalse(result.succeeded);
        assertEquals("DENIED", result.status);
        assertEquals("Not approved", result.message);
    }

    @Test public void inferenceInterfaceCanStreamAndResetCancellation() {
        final AtomicBoolean stopped = new AtomicBoolean(false);
        InferenceEngine fake = new InferenceEngine() {
            @Override public long loadModel(String modelPath, String projectorPath) { return 1L; }
            @Override public void unloadModel(long handle) { }
            @Override public String generate(long handle, String prompt, String mediaPath,
                                             int maxTokens, TokenSink sink) {
                if (stopped.get()) return "STOPPED";
                sink.onToken("safe");
                return null;
            }
            @Override public void stop() { stopped.set(true); }
            @Override public void resetStop() { stopped.set(false); }
        };

        StringBuilder streamed = new StringBuilder();
        assertEquals(1L, fake.loadModel("fake", null));
        assertNull(fake.generate(1L, "hi", null, 1, streamed::append));
        assertEquals("safe", streamed.toString());

        fake.stop();
        streamed.setLength(0);
        assertEquals("STOPPED", fake.generate(1L, "hi", null, 1, streamed::append));
        assertTrue(stopped.get());
        assertTrue(streamed.length() == 0);

        fake.resetStop();
        assertNull(fake.generate(1L, "retry", null, 1, streamed::append));
        assertEquals("safe", streamed.toString());
    }
}
