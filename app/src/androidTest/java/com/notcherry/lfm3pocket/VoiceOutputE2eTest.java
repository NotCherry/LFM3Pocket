package com.notcherry.lfm3pocket;

import android.content.Context;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import java.io.File;
import static org.junit.Assert.*;

/** EMU-FAKE: push-to-talk → local ASR/assistant → fixture spoken playback and Stop. */
public final class VoiceOutputE2eTest {
    private TestLfm3PocketApplication app;
    private Context context;
    private UiDevice device;
    private String pkg;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        pkg = context.getPackageName();
        app = (TestLfm3PocketApplication) context.getApplicationContext();
        app.resetCapture(false);
        app.resetPlayback();
        app.capturePermission(true);
        app.scriptedEngine().resetForTest();
        app.scriptedEngine().allowFirstTurnSuccess();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        File model = new File(context.getFilesDir(), "voice-output-ui-model.gguf");
        TestGgufFixtures.model(model);
        context.getSharedPreferences("MainActivity", 0).edit().clear()
                .putString("model", model.getAbsolutePath()).commit();
        File speech = new File(context.getCacheDir(), "speech");
        if (speech.isDirectory()) {
            File[] files = speech.listFiles();
            if (files != null) for (File file : files) file.delete();
        }
    }

    @After public void cleanup() {
        app.capturePermission(false);
        app.resetPlayback();
    }

    @Test public void recordedVoiceProducesSpokenPlaybackThatStopCanInterrupt() throws Exception {
        app.holdPlayback();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_send").enabled(true)), 12000));
            UiObject2 record = device.wait(Until.findObject(By.res(pkg, "voice_record").enabled(true)), 12000);
            assertNotNull(record);
            record.click();
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_status")
                    .textContains("Recording voice input")), 12000));
            assertTrue(app.awaitCaptured());
            record.click();
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_status")
                    .textContains("Voice recording attached")), 12000));

            UiObject2 input = device.wait(Until.findObject(By.res(pkg, "chat_input")), 12000);
            assertNotNull(input);
            input.setText("Please reply with spoken audio.");
            UiObject2 send = device.wait(Until.findObject(By.res(pkg, "chat_send").enabled(true)), 12000);
            assertNotNull(send);
            send.click();

            assertTrue(app.scriptedEngine().awaitGenerationStarted());
            assertEquals(1, app.scriptedEngine().asrCallCount());

            long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
            while (app.scriptedEngine().speechCallCount() == 0
                    && android.os.SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(25);
            }
            assertEquals("Voice turns must request local speech synthesis", 1,
                    app.scriptedEngine().speechCallCount());
            assertTrue(app.awaitPlaybackStarted());
            assertEquals(1, app.playbackOpened());

            UiObject2 stop = device.wait(Until.findObject(By.res(pkg, "chat_stop").enabled(true)), 12000);
            assertNotNull("Stop must stay enabled while spoken playback is active", stop);
            stop.click();
            app.releasePlayback();
            assertTrue(app.awaitPlaybackReleased());
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_status")
                    .textContains("Spoken response")), 12000));
        }
    }
}
