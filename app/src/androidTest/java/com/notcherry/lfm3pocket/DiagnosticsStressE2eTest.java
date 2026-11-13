package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * R02 EMU-FAKE stress/lifecycle: unload/reload loops, stop recovery, background mic release,
 * and local diagnostics breadcrumbs with an explicit no-telemetry contract.
 */
@RunWith(AndroidJUnit4.class)
public final class DiagnosticsStressE2eTest {
    private static final long UI_TIMEOUT_MS = 20000L;
    private TestLfm3PocketApplication app;
    private Context context;
    private UiDevice device;
    private String pkg;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        pkg = context.getPackageName();
        app = (TestLfm3PocketApplication) context.getApplicationContext();
        app.scriptedEngine().resetForTest();
        app.resetCapture(false);
        app.capturePermission(true);
        app.diagnostics().clear();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        File model = new File(context.getFilesDir(), "diagnostics-stress-model.gguf");
        TestGgufFixtures.model(model);
        assertTrue(context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().clear()
                .putString("model", model.getAbsolutePath())
                .putString("model_name", "Stress local model")
                .putBoolean("search_enabled", false)
                .commit());
    }

    @After public void cleanup() {
        app.capturePermission(false);
    }

    private UiObject2 waitEnabled(String id) {
        UiObject2 object = device.wait(Until.findObject(By.res(pkg, id).enabled(true)), UI_TIMEOUT_MS);
        assertNotNull("Expected enabled control: " + id, object);
        return object;
    }

    private void clickText(String text) {
        UiObject2 button = device.wait(Until.findObject(By.text(text).enabled(true)), UI_TIMEOUT_MS);
        assertNotNull("Expected control: " + text, button);
        button.click();
    }

    @Test public void unloadReloadStopLoopsRecoverWithoutStrandedMicOrTelemetry() throws Exception {
        assertFalse(app.diagnostics().uploadsTelemetry());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            waitEnabled("chat_send");
            assertTrue("Initial model load must leave a local diagnostic breadcrumb",
                    app.diagnostics().count(LocalDiagnostics.Kind.MODEL_LOAD) >= 1);

            for (int i = 0; i < 3; i++) {
                clickText("Unload model");
                assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_send").enabled(false)), UI_TIMEOUT_MS));
                clickText("Load model");
                waitEnabled("chat_send");
                app.diagnostics().record(LocalDiagnostics.Kind.STRESS, 0, "unload-reload-" + i);

                TestLfm3PocketApplication.ScriptedInferenceEngine engine = app.scriptedEngine();
                engine.resetForTest();
                engine.holdFirstToken();
                UiObject2 input = device.wait(Until.findObject(By.res(pkg, "chat_input")), UI_TIMEOUT_MS);
                assertNotNull(input);
                input.setText("Stress turn " + i);
                waitEnabled("chat_send").click();
                assertTrue(engine.awaitGenerationStarted());
                engine.releaseFirstToken();
                assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_transcript")
                        .textContains("FAKE_STREAM_BEGIN")), UI_TIMEOUT_MS));
                waitEnabled("chat_stop").click();
                assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_live_status")
                        .textContains("Response stopped")), UI_TIMEOUT_MS));
                waitEnabled("chat_send");
                app.diagnostics().record(LocalDiagnostics.Kind.TURN_COMPLETE, 0, "stopped-stress-" + i);
            }

            int openedBefore = app.captureOpened();
            waitEnabled("voice_record").click();
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_status")
                    .textContains("Recording voice input")), UI_TIMEOUT_MS));
            assertTrue(app.awaitCaptured());
            scenario.moveToState(Lifecycle.State.CREATED);
            assertTrue("Background must release the mic source", app.awaitCaptureReleased());
            scenario.moveToState(Lifecycle.State.RESUMED);
            waitEnabled("chat_send");
            assertEquals("Background cancel must not reopen capture automatically",
                    openedBefore + 1, app.captureOpened());

            File diagnosticsFile = new File(context.getFilesDir(), "diagnostics.ndjson");
            app.diagnostics().writeTo(diagnosticsFile);
            assertTrue(diagnosticsFile.isFile());
            assertTrue(diagnosticsFile.length() > 0);
            assertTrue(app.diagnostics().count(LocalDiagnostics.Kind.MODEL_LOAD) >= 4);
            assertTrue(app.diagnostics().count(LocalDiagnostics.Kind.STRESS) >= 3);
            assertFalse(app.diagnostics().uploadsTelemetry());
        }
    }
}
