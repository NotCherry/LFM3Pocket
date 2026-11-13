package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

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
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * EMU-FAKE journey: camera still + voice question + follow-up with the deterministic
 * multimodal adapter. Real Omni visual grounding remains an ARM64 acceptance gate.
 */
@RunWith(AndroidJUnit4.class)
public final class MultimodalConversationE2eTest {
    private static final long UI_TIMEOUT_MS = 20000L;
    private TestLfm3PocketApplication app;
    private Context context;
    private UiDevice device;
    private String pkg;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        pkg = context.getPackageName();
        app = (TestLfm3PocketApplication) context.getApplicationContext();
        app.resetCapture(false);
        app.capturePermission(true);
        app.scriptedEngine().resetForTest();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        File model = new File(context.getFilesDir(), "multimodal-ui-model.gguf");
        TestGgufFixtures.model(model);
        assertTrue(context.getSharedPreferences("MainActivity", 0).edit().clear()
                .putString("model", model.getAbsolutePath())
                .putBoolean("search_enabled", false).commit());
        File recordings = new File(context.getCacheDir(), "recordings");
        if (recordings.isDirectory()) for (File file : recordings.listFiles()) file.delete();
    }

    @After public void cleanup() {
        app.capturePermission(false);
    }

    private UiObject2 waitFor(String id) {
        UiObject2 object = device.wait(Until.findObject(By.res(pkg, id)), UI_TIMEOUT_MS);
        assertNotNull("Expected UI element: " + id, object);
        return object;
    }

    private UiObject2 waitEnabled(String id) {
        UiObject2 object = device.wait(Until.findObject(By.res(pkg, id).enabled(true)), UI_TIMEOUT_MS);
        assertNotNull("Expected enabled UI control: " + id, object);
        return object;
    }

    private void answerCameraPermissionIfNeeded() {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            return;
        UiObject2 choice = device.wait(Until.findObject(By.text(Pattern.compile(
                "(?i)(while using the app|only this time|allow)"))), UI_TIMEOUT_MS);
        assertNotNull("Camera runtime permission choice was not shown", choice);
        choice.click();
    }

    private static int occurrences(String value, String token) {
        return value.split(java.util.regex.Pattern.quote(token), -1).length - 1;
    }

    @Test public void cameraStillPlusVoiceQuestionKeepsImageForFollowUpAndCleansAudio() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            waitEnabled("chat_send");

            waitEnabled("chat_camera").click();
            answerCameraPermissionIfNeeded();
            waitFor("camera_preview");
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "camera_status").textContains("Camera ready")),
                    UI_TIMEOUT_MS));
            waitEnabled("camera_capture").click();
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_status").textContains("photo attached")),
                    UI_TIMEOUT_MS));

            final String[] imagePath = new String[1];
            scenario.onActivity(activity -> {
                String[] pending = activity.pendingMediaPathsForTest();
                assertEquals(1, pending.length);
                imagePath[0] = pending[0];
            });
            assertNotNull(imagePath[0]);
            File photo = new File(imagePath[0]);
            assertTrue(photo.isFile());

            waitEnabled("voice_record").click();
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_status")
                    .textContains("Recording voice input")), UI_TIMEOUT_MS));
            assertTrue(app.awaitCaptured());
            waitEnabled("voice_record").click();
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_status")
                    .textContains("Voice recording attached")), UI_TIMEOUT_MS));
            assertTrue(app.awaitCaptureReleased());

            final String[] audioPath = new String[1];
            scenario.onActivity(activity -> {
                String[] pending = activity.pendingMediaPathsForTest();
                assertEquals(Arrays.asList(imagePath[0], pending[pending.length - 1]).toString(),
                        2, pending.length);
                assertEquals(imagePath[0], pending[0]);
                audioPath[0] = pending[1];
            });
            assertNotNull(audioPath[0]);
            File wav = new File(audioPath[0]);
            assertTrue(wav.isFile());
            assertNotNull("Image preview should remain visible before the combined send",
                    device.wait(Until.findObject(By.res(pkg, "image_attachment")), UI_TIMEOUT_MS));

            TestLfm3PocketApplication.ScriptedInferenceEngine engine = app.scriptedEngine();
            engine.resetForTest();
            engine.holdFirstToken();
            // Avoid Compose IME/focus races after CameraX return; exercise the same send() path.
            scenario.onActivity(activity -> activity.sendForTest("What is in the picture?"));
            assertTrue("Combined camera+voice turn should reach the fake local adapter",
                    engine.awaitGenerationStarted());

            assertEquals(1, engine.asrCallCount());
            assertEquals(java.util.Collections.singletonList(audioPath[0]), engine.mediaByCall().get(0));
            assertEquals(Arrays.asList(imagePath[0], audioPath[0]), engine.mediaByCall().get(1));
            assertEquals(2, occurrences(engine.promptsByCall().get(1), "<__media__>"));
            assertTrue(engine.lastPrompt().contains("What is in the picture?"));
            assertFalse(engine.lastPrompt().contains("search.example"));

            engine.releaseFirstToken();
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_transcript")
                    .textContains("FAKE_STREAM_BEGIN")), UI_TIMEOUT_MS));
            waitEnabled("chat_stop").click();
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_live_status")
                    .textContains("Response stopped")), UI_TIMEOUT_MS));
            waitEnabled("chat_send");

            long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
            while (wav.exists() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25);
            assertFalse("Turn-scoped audio must be deleted after the combined response", wav.exists());
            assertTrue("Session-scoped photo must remain for follow-up", photo.exists());

            scenario.onActivity(activity -> activity.sendForTest("What color was it?"));
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_transcript")
                    .textContains("FAKE_RETRY_COMPLETE")), UI_TIMEOUT_MS));
            assertEquals(java.util.Collections.singletonList(imagePath[0]), engine.mediaByCall().get(2));
            assertEquals(1, occurrences(engine.promptsByCall().get(2), "<__media__>"));
            assertTrue(engine.lastPrompt().contains("What color was it?"));
            assertTrue(engine.lastPrompt().contains("What is in the picture?"));
            assertEquals("Follow-up text must not trigger another ASR pass", 1, engine.asrCallCount());

            UiObject2 clear = device.wait(Until.findObject(By.text("Clear")), UI_TIMEOUT_MS);
            assertNotNull(clear);
            clear.click();
            deadline = android.os.SystemClock.elapsedRealtime() + 5000;
            while (photo.exists() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25);
            assertFalse("Clearing the conversation must delete the retained photo", photo.exists());
        }
    }
}
