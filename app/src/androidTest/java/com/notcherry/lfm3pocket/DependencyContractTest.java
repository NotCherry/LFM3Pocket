package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Map;

/**
 * Contract tests intentionally exercise no JNI and do not require model weights.
 * Future emulator UI tests can install an Application subclass with these fakes.
 */
@RunWith(AndroidJUnit4.class)
public final class DependencyContractTest {
    private static final class FakeEngine implements InferenceEngine {
        boolean stopped;
        String scriptedError;

        @Override public long loadModel(String modelPath, String projectorPath) {
            return 7L;
        }

        @Override public void unloadModel(long handle) { }

        @Override public String generate(long handle, String prompt, String mediaPath,
                                         int maxTokens, TokenSink sink) {
            if (scriptedError != null) return scriptedError;
            if (stopped) return null;
            sink.onToken("first");
            if (!stopped) sink.onToken(" second");
            return null;
        }

        @Override public void stop() {
            stopped = true;
        }

        @Override public void resetStop() {
            stopped = false;
        }
    }

    private static final class TestApp extends Lfm3PocketApplication {
        final FakeEngine model = new FakeEngine();
        final AndroidActionExecutor actions = (name, arguments) ->
                new AndroidActionExecutor.ActionResult(
                        "toggle_flashlight".equals(name), "FAKE_RESULT", name);

        @Override protected InferenceEngine createInferenceEngine() {
            return model;
        }

        @Override protected AndroidActionExecutor createActionExecutor() {
            return actions;
        }
    }

    @Test public void appDependenciesAreInjectableAndStable() {
        TestApp app = new TestApp();
        assertSame(app.model, app.inferenceEngine());
        assertSame(app.model, app.inferenceEngine());
        assertSame(app.actions, app.actionExecutor());
    }

    @Test public void fakeEmitsDeterministicTokensAndSupportsCancellation() {
        FakeEngine model = new FakeEngine();
        long handle = model.loadModel("test.gguf", null);
        assertEquals(7L, handle);

        StringBuilder text = new StringBuilder();
        assertNull(model.generate(handle, "question", null, 2, text::append));
        assertEquals("first second", text.toString());

        model.resetStop();
        text.setLength(0);
        assertNull(model.generate(handle, "question", null, 2, piece -> {
            text.append(piece);
            model.stop();
        }));
        assertEquals("first", text.toString());

        model.resetStop();
        text.setLength(0);
        assertNull(model.generate(handle, "retry", null, 2, text::append));
        assertEquals("first second", text.toString());
    }

    @Test public void fakeReturnsErrorWithoutEmittingTokens() {
        FakeEngine model = new FakeEngine();
        model.scriptedError = "MODEL_UNAVAILABLE";
        StringBuilder text = new StringBuilder();
        assertEquals("MODEL_UNAVAILABLE",
                model.generate(7L, "prompt", null, 5, text::append));
        assertEquals(0, text.length());
    }

    @Test public void deviceActionsHaveDeterministicTestResultsAndDefaultDeny() {
        TestApp testApp = new TestApp();
        AndroidActionExecutor.ActionResult ok =
                testApp.actionExecutor().execute("toggle_flashlight", Map.of("on", "true"));
        assertTrue(ok.succeeded);
        assertEquals("FAKE_RESULT", ok.status);

        AndroidActionExecutor.ActionResult reject =
                testApp.actionExecutor().execute("unsupported", Map.of());
        assertFalse(reject.succeeded);

        Lfm3PocketApplication production = new Lfm3PocketApplication();
        AndroidActionExecutor.ActionResult deny =
                production.actionExecutor().execute("toggle_flashlight", Map.of());
        assertFalse(deny.succeeded);
        assertEquals("NOT_IMPLEMENTED", deny.status);
    }
}
