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
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Device smoke + chat UI E2E test for optional Cactus Whistle STT integration.
 *
 * <p>Verifies provider selection/persistence, safe failure handling, and the real Chat tap-to-record
 * -> Whistle STT -> text prompt/chat transcript flow (regression for #296).
 */
@RunWith(AndroidJUnit4.class)
public class CactusWhistleSttSmokeTest {
    private static final long UI_TIMEOUT_MS = 20_000L;

    @After public void resetPrefs() {
        CactusWhistleBridge.testHook = null;
        CactusWhistleBridge.resetLoadedModel();
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        if (app instanceof TestLfm3PocketApplication) {
            ((TestLfm3PocketApplication) app).capturePermission(false);
        }
        RuntimePreferences prefs = new RuntimePreferences(app);
        prefs.setSpeechInputMode(SpeechInputMode.OMNI_AUDIO);
        prefs.setCactusWhistleModelPath("");
    }

    @Test public void defaultsToOmniAndPersistsWhistleSelection() {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        RuntimePreferences prefs = new RuntimePreferences(app);
        prefs.setSpeechInputMode(SpeechInputMode.OMNI_AUDIO);
        assertEquals(SpeechInputMode.OMNI_AUDIO, prefs.speechInputMode());
        prefs.setSpeechInputMode(SpeechInputMode.CACTUS_WHISTLE_STT);
        assertEquals(SpeechInputMode.CACTUS_WHISTLE_STT, new RuntimePreferences(app).speechInputMode());
        assertEquals(SpeechInputMode.CACTUS_WHISTLE_STT, SpeechInputProviders.current(app).mode());
    }

    @Test public void unavailableWhistleFailsSafelyWithoutLlmAction() {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        RuntimePreferences prefs = new RuntimePreferences(app);
        prefs.setSpeechInputMode(SpeechInputMode.CACTUS_WHISTLE_STT);
        prefs.setCactusWhistleModelPath("");
        SttProvider provider = SpeechInputProviders.current(app);
        assertEquals(SpeechInputMode.CACTUS_WHISTLE_STT, provider.mode());
        SttAvailability availability = provider.availability(app);
        assertFalse("Whistle should be unavailable without runtime/model configured", availability.available);
        SttResult result = provider.transcribe(app, new File(app.getCacheDir(), "missing.wav"), new AtomicBoolean(false));
        assertNotNull(result);
        assertTrue(result.status == SttResult.Status.UNAVAILABLE || result.status == SttResult.Status.ERROR);
    }

    @Test public void switchingBackToOmniRestoresDefaultProvider() {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        RuntimePreferences prefs = new RuntimePreferences(app);
        prefs.setSpeechInputMode(SpeechInputMode.CACTUS_WHISTLE_STT);
        prefs.setSpeechInputMode(SpeechInputMode.OMNI_AUDIO);
        assertEquals(SpeechInputMode.OMNI_AUDIO, SpeechInputProviders.current(app).mode());
        assertTrue(SpeechInputProviders.current(app) instanceof OmniSpeechInputProvider);
    }

    @Test public void chatVoiceInputTapToRecordTranscribesViaWhistleAndSendsToChat() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String pkg = context.getPackageName();
        TestLfm3PocketApplication app = (TestLfm3PocketApplication) context.getApplicationContext();
        app.resetCapture(false);
        app.capturePermission(true);
        app.scriptedEngine().resetForTest();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

        File model = new File(context.getFilesDir(), "whistle-chat-model.gguf");
        TestGgufFixtures.model(model);
        File whistle = new File(context.getFilesDir(), "cactus/whistle.cact");
        whistle.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(whistle)) {
            out.write(new byte[] { 1, 2, 3, 4 });
        }
        assertTrue(context.getSharedPreferences(RuntimePreferences.FILE, 0).edit().clear()
                .putString("model", model.getAbsolutePath())
                .putString("speech_input_mode", SpeechInputMode.CACTUS_WHISTLE_STT.wireName)
                .putString("cactus_whistle_model", whistle.getAbsolutePath())
                .putBoolean("search_enabled", false).commit());

        CactusWhistleBridge.testHook = new CactusWhistleBridge.TestHook() {
            @Override public SttAvailability availability(String modelPath) {
                return SttAvailability.ready();
            }
            @Override public SttResult transcribe(String modelPath, float[] pcm16kMono, AtomicBoolean cancelled) {
                assertTrue("Expected captured 16 kHz samples", pcm16kMono != null && pcm16kMono.length >= 4000);
                return SttResult.ok("Hello from Cactus Whistle");
            }
        };

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            UiObject2 send = device.wait(Until.findObject(By.res(pkg, "chat_send").enabled(true)), UI_TIMEOUT_MS);
            assertNotNull("Expected enabled chat_send", send);
            UiObject2 record = device.wait(Until.findObject(By.res(pkg, "voice_record").enabled(true)), UI_TIMEOUT_MS);
            assertNotNull("Expected enabled voice_record", record);
            // Single tap must start recording (not be hijacked by hold-to-talk PTT).
            record.click();
            assertNotNull("Tap must start recording in Whistle mode",
                    device.wait(Until.findObject(By.res(pkg, "chat_status").textContains("Recording voice input")),
                            UI_TIMEOUT_MS));
            assertTrue(app.awaitCaptured());

            app.scriptedEngine().holdFirstToken();
            record = device.wait(Until.findObject(By.res(pkg, "voice_record").enabled(true)), UI_TIMEOUT_MS);
            assertNotNull(record);
            record.click();
            assertTrue(app.awaitCaptureReleased());
            assertTrue("Recognized Whistle transcript must submit to chat engine",
                    app.scriptedEngine().awaitGenerationStarted());
            assertEquals("Whistle STT must not invoke Omni ASR pass", 0, app.scriptedEngine().asrCallCount());
            assertTrue("Prompt must contain Whistle transcript",
                    app.scriptedEngine().lastPrompt().contains("Hello from Cactus Whistle"));
            app.scriptedEngine().releaseFirstToken();
            app.scriptedEngine().completeFirstTurn();
            assertNotNull("Transcribed user turn and reply must appear in chat transcript",
                    device.wait(Until.findObject(By.res(pkg, "chat_transcript")
                            .textContains("Hello from Cactus Whistle")), UI_TIMEOUT_MS));
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_transcript")
                    .textContains("FAKE_STREAM_BEGIN")), UI_TIMEOUT_MS));
        }
    }

    @Test public void unavailableWhistleTapShowsErrorWithoutOpeningMic() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String pkg = context.getPackageName();
        TestLfm3PocketApplication app = (TestLfm3PocketApplication) context.getApplicationContext();
        app.resetCapture(false);
        app.capturePermission(true);
        app.scriptedEngine().resetForTest();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

        File model = new File(context.getFilesDir(), "whistle-unavail-model.gguf");
        TestGgufFixtures.model(model);
        assertTrue(context.getSharedPreferences(RuntimePreferences.FILE, 0).edit().clear()
                .putString("model", model.getAbsolutePath())
                .putString("speech_input_mode", SpeechInputMode.CACTUS_WHISTLE_STT.wireName)
                .putString("cactus_whistle_model", "")
                .putBoolean("search_enabled", false).commit());

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_send").enabled(true)), UI_TIMEOUT_MS));
            UiObject2 record = device.wait(Until.findObject(By.res(pkg, "voice_record").enabled(true)), UI_TIMEOUT_MS);
            assertNotNull(record);
            record.click();
            assertNotNull("Expected upfront unavailable message",
                    device.wait(Until.findObject(By.res(pkg, "chat_status").textContains("unavailable")),
                            UI_TIMEOUT_MS));
            assertEquals("Microphone must not open when Whistle STT is unavailable", 0, app.captureOpened());
        }
    }
}
