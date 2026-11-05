package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Real emulator UI interactions using a deterministic fake model, never LFM3 weights.
 * Device.wait(Until...) uses conditions and deadlines rather than fixed sleeps.
 */
@RunWith(AndroidJUnit4.class)
public final class ChatUiE2eTest {
    private static final long UI_TIMEOUT_MS = 12000L;
    private TestLfm3PocketApplication app;
    private UiDevice device;
    private String packageName;

    @Before public void prepareFakeModel() throws IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        app = (TestLfm3PocketApplication) context.getApplicationContext();
        app.scriptedEngine().resetForTest();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        packageName = context.getPackageName();

        // MainActivity restores an existing private model path on startup.
        // The injected fake accepts this invalid stub without ever invoking JNI.
        File file = new File(context.getFilesDir(), "ui-e2e-dummy.gguf");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(new byte[] { 'G', 'G', 'U', 'F' });
        }
        assertTrue(context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
                .edit().clear()
                .putString("model", file.getAbsolutePath())
                .putBoolean("search_enabled", false)
                .commit());
    }

    private UiObject2 awaitEnabled(String id) {
        UiObject2 object = device.wait(
                Until.findObject(By.res(packageName, id).enabled(true)), UI_TIMEOUT_MS);
        assertNotNull("Expected enabled UI control: " + id, object);
        return object;
    }

    private UiObject2 awaitTranscript(String token) {
        UiObject2 object = device.wait(Until.findObject(
                By.res(packageName, "chat_transcript").textContains(token)), UI_TIMEOUT_MS);
        assertNotNull("Expected rendered transcript token: " + token, object);
        return object;
    }

    @Test public void launchStreamCancelAndRetryRecoversVisibleChatControls() {
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            // Real Activity launch, async fake model load, and UI readiness.
            awaitEnabled("chat_send");
            assertTrue(app.scriptedEngine().loadedPath().endsWith("ui-e2e-dummy.gguf"));

            UiObject2 input = device.wait(
                    Until.findObject(By.res(packageName, "chat_input")), UI_TIMEOUT_MS);
            assertNotNull(input);
            input.setText("First prompt");
            awaitEnabled("chat_send").click();

            // The scripted model blocks on a latch after the first streamed piece.
            awaitTranscript("FAKE_STREAM_BEGIN");
            assertFalse(device.findObject(By.res(packageName, "chat_send")).isEnabled());
            awaitEnabled("chat_stop").click();

            // stop() releases the model; UI must recover before the next turn.
            awaitEnabled("chat_send");
            awaitTranscript("FAKE_STREAM_BEGIN");
            UiObject2 retryInput = device.findObject(By.res(packageName, "chat_input"));
            assertNotNull(retryInput);
            retryInput.setText("Retry prompt");
            awaitEnabled("chat_send").click();

            UiObject2 transcript = awaitTranscript("FAKE_RETRY_COMPLETE");
            assertTrue(transcript.getText().contains("First prompt"));
            assertTrue(transcript.getText().contains("Retry prompt"));
            assertEquals(2, app.scriptedEngine().callCount());
            awaitEnabled("chat_send");
            assertFalse(device.findObject(By.res(packageName, "chat_stop")).isEnabled());
        }
    }
}
