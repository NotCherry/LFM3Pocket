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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * EMU-FAKE voice journey: push-to-talk → local ASR/assistant text → Speak reply playback →
 * stop → follow-up. Physical Omni Polish/English quality remains an ARM64 acceptance gate.
 */
@RunWith(AndroidJUnit4.class)
public final class VoiceOutputE2eTest {
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
        app.resetPlayback();
        app.scriptedEngine().resetForTest();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        File model = new File(context.getFilesDir(), "voice-output-ui-model.gguf");
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

    private UiObject2 waitEnabled(String id) {
        UiObject2 object = device.wait(Until.findObject(By.res(pkg, id).enabled(true)), UI_TIMEOUT_MS);
        assertNotNull("Expected enabled control: " + id, object);
        return object;
    }

    private void statusContains(String text) {
        assertNotNull(text, device.wait(Until.findObject(By.res(pkg, "chat_status").textContains(text)), UI_TIMEOUT_MS));
    }

    @Test public void pushToTalkSpeakReplyStopAndFollowUp() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            waitEnabled("chat_send");
            waitEnabled("voice_record").click();
            statusContains("Recording voice input");
            assertTrue(app.awaitCaptured());
            waitEnabled("voice_record").click();
            statusContains("Voice recording attached");
            assertTrue(app.awaitCaptureReleased());

            final String[] wavPath = new String[1];
            scenario.onActivity(activity -> wavPath[0] = activity.pendingMediaPathForTest());
            assertNotNull(wavPath[0]);
            File wav = new File(wavPath[0]);
            assertTrue(wav.isFile());

            UiObject2 input = device.wait(Until.findObject(By.res(pkg, "chat_input")), UI_TIMEOUT_MS);
            assertNotNull(input);
            input.setText("What did I say?");
            TestLfm3PocketApplication.ScriptedInferenceEngine engine = app.scriptedEngine();
            engine.holdFirstToken();
            waitEnabled("chat_send").click();
            assertTrue(engine.awaitGenerationStarted());
            assertEquals(1, engine.asrCallCount());
            assertTrue(engine.lastAsrPrompt().contains("Perform ASR."));
            engine.releaseFirstToken();
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_transcript")
                    .textContains("FAKE_STREAM_BEGIN")), UI_TIMEOUT_MS));
            engine.completeFirstTurn();
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_live_status")
                    .textContains("Response complete")), UI_TIMEOUT_MS));
            waitEnabled("chat_send");
            long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
            while (wav.exists() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25);
            assertFalse(wav.exists());

            app.resetPlayback();
            waitEnabled("voice_speak").click();
            assertTrue("Speak reply must open the injectable playback track", app.awaitPlaybackStarted());
            assertEquals(1, engine.speechCallCount());
            assertTrue(engine.lastSpeechPrompt().contains("Perform TTS"));
            assertTrue(engine.lastSpeechPrompt().contains("FAKE_STREAM_BEGIN"));
            assertTrue(app.playbackWritten() > 0);
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "voice_speak").textContains("Stop speech")),
                    UI_TIMEOUT_MS));
            waitEnabled("voice_speak").click();
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_status")
                    .textContains("Spoken response stopped")), UI_TIMEOUT_MS));
            deadline = android.os.SystemClock.elapsedRealtime() + 5000;
            while (app.playbackReleased() < 1 && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25);
            assertEquals(1, app.playbackReleased());

            input = device.wait(Until.findObject(By.res(pkg, "chat_input")), UI_TIMEOUT_MS);
            assertNotNull(input);
            input.setText("Say that again briefly.");
            waitEnabled("chat_send").click();
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_transcript")
                    .textContains("FAKE_RETRY_COMPLETE")), UI_TIMEOUT_MS));
            assertTrue(engine.lastPrompt().contains("Say that again briefly."));
            assertTrue(engine.lastPrompt().contains("What did I say?"));
            assertEquals("Follow-up text must not record again", 1, engine.asrCallCount());
        }
    }
}
