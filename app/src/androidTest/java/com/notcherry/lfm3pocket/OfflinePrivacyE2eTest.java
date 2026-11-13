package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.TrafficStats;
import android.os.Process;
import android.provider.Settings;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.UiScrollable;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * R01 EMU-FAKE privacy slice: airplane-mode speech/vision stay local (UID TX flat) and
 * SearXNG remains HTTPS opt-in. Physical ARM64 airplane evidence remains a release gate.
 */
@RunWith(AndroidJUnit4.class)
public final class OfflinePrivacyE2eTest {
    private static final long UI_TIMEOUT_MS = 20000L;
    private TestLfm3PocketApplication app;
    private Context context;
    private UiDevice device;
    private String pkg;
    private String previousAirplane;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        pkg = context.getPackageName();
        app = (TestLfm3PocketApplication) context.getApplicationContext();
        app.scriptedEngine().resetForTest();
        app.resetPlayback();
        app.capturePermission(true);
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        File model = new File(context.getFilesDir(), "offline-privacy-ui-model.gguf");
        TestGgufFixtures.model(model);
        assertTrue(context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().clear()
                .putString("model", model.getAbsolutePath())
                .putBoolean("search_enabled", false)
                .putString("search_endpoint", "")
                .commit());
        previousAirplane = device.executeShellCommand("settings get global airplane_mode_on").trim();
    }

    @After public void cleanup() throws Exception {
        app.capturePermission(false);
        restoreAirplane();
    }

    private void enableAirplane() throws Exception {
        device.executeShellCommand("settings put global airplane_mode_on 1");
        device.executeShellCommand("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true");
        device.executeShellCommand("svc wifi disable");
        device.executeShellCommand("svc data disable");
        assertEquals(1, Settings.Global.getInt(app.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0));
    }

    private void restoreAirplane() throws Exception {
        String restored = "1".equals(previousAirplane) ? "1" : "0";
        device.executeShellCommand("settings put global airplane_mode_on " + restored);
        device.executeShellCommand("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state " +
                ("1".equals(restored) ? "true" : "false"));
        if ("0".equals(restored)) {
            device.executeShellCommand("svc wifi enable");
            device.executeShellCommand("svc data enable");
        }
    }

    private UiObject2 waitEnabled(String id) {
        UiObject2 object = device.wait(Until.findObject(By.res(pkg, id).enabled(true)), UI_TIMEOUT_MS);
        assertNotNull("Expected enabled control: " + id, object);
        return object;
    }

    private void assertNoUidTxGrowth(long before) {
        assertTrue("UID network accounting must be available", before >= 0);
        assertEquals("Offline local inference must not transmit for this app UID",
                before, TrafficStats.getUidTxBytes(Process.myUid()));
    }

    @Test public void searchDefaultsOffAndRejectsCleartextEndpoint() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            waitEnabled("chat_send");
            UiObject2 search = scrollTo("web_search");
            assertTrue(search.getText().contains("Web search: off"));
            search.click();
            UiObject2 endpoint = device.wait(Until.findObject(By.res(pkg, "search_endpoint")), UI_TIMEOUT_MS);
            assertNotNull(endpoint);
            endpoint.setText("http://searx.example/search");
            UiObject2 enabled = device.wait(Until.findObject(By.res(pkg, "search_enabled")), UI_TIMEOUT_MS);
            assertNotNull(enabled);
            if (!enabled.isChecked()) enabled.click();
            assertNotNull(device.wait(Until.findObject(By.text("Save")), UI_TIMEOUT_MS)).click();
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_status")
                    .textContains("Use an HTTPS SearXNG search endpoint")), UI_TIMEOUT_MS));
            search = scrollTo("web_search");
            assertTrue("Cleartext endpoint must leave search disabled",
                    search.getText().contains("Web search: off"));
            assertFalse(context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
                    .getBoolean("search_enabled", true));
        }
    }

    @Test public void airplaneModeSpeechPlaybackDoesNotTransmitNetworkBytes() throws Exception {
        enableAirplane();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            waitEnabled("chat_send");
            long txBefore = TrafficStats.getUidTxBytes(Process.myUid());
            UiObject2 input = device.wait(Until.findObject(By.res(pkg, "chat_input")), UI_TIMEOUT_MS);
            assertNotNull(input);
            input.setText("Offline spoken reply");
            TestLfm3PocketApplication.ScriptedInferenceEngine engine = app.scriptedEngine();
            engine.holdFirstToken();
            waitEnabled("chat_send").click();
            assertTrue(engine.awaitGenerationStarted());
            engine.releaseFirstToken();
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_transcript")
                    .textContains("FAKE_STREAM_BEGIN")), UI_TIMEOUT_MS));
            engine.completeFirstTurn();
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_live_status")
                    .textContains("Response complete")), UI_TIMEOUT_MS));
            waitEnabled("chat_send");

            app.resetPlayback();
            waitEnabled("voice_speak").click();
            assertTrue(app.awaitPlaybackStarted());
            assertEquals(1, engine.speechCallCount());
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "voice_speak")
                    .textContains("Stop speech")), UI_TIMEOUT_MS));
            waitEnabled("voice_speak").click();
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_status")
                    .textContains("Spoken response stopped")), UI_TIMEOUT_MS));
            assertNoUidTxGrowth(txBefore);
        }
    }

    @Test public void airplaneModeImageTurnDoesNotTransmitNetworkBytes() throws Exception {
        enableAirplane();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            waitEnabled("chat_send");
            long txBefore = TrafficStats.getUidTxBytes(Process.myUid());
            chooseImage("valid-photo.jpg");
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "image_preview")), UI_TIMEOUT_MS));
            TestLfm3PocketApplication.ScriptedInferenceEngine engine = app.scriptedEngine();
            engine.resetForTest();
            engine.holdFirstToken();
            // Avoid Compose IME/focus races after returning from the SAF picker.
            scenario.onActivity(activity -> activity.sendForTest("What is in this offline photo?"));
            assertTrue(engine.awaitGenerationStarted());
            engine.releaseFirstToken();
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_transcript")
                    .textContains("FAKE_STREAM_BEGIN")), UI_TIMEOUT_MS));
            waitEnabled("chat_stop").click();
            waitEnabled("chat_send");
            assertTrue("Image path must reach the local fake adapter",
                    engine.lastMediaPath() != null || !engine.mediaByCall().isEmpty());
            assertNoUidTxGrowth(txBefore);
        }
    }

    private UiObject2 scrollTo(String id) throws Exception {
        new UiScrollable(new UiSelector().resourceId(pkg + ":id/model_configuration_scroll"))
                .scrollIntoView(new UiSelector().resourceId(pkg + ":id/" + id));
        UiObject2 object = device.wait(Until.findObject(By.res(pkg, id)), UI_TIMEOUT_MS);
        assertNotNull("Expected scrolled control: " + id, object);
        return object;
    }

    private void chooseImage(String name) throws Exception {
        waitEnabled("chat_photo").click();
        java.util.regex.Pattern documentDescription = java.util.regex.Pattern.compile(
                "^" + java.util.regex.Pattern.quote(name) + ",.*");
        UiObject2 file = device.wait(Until.findObject(By.desc(documentDescription)), 1500);
        if (file == null) {
            UiObject2 roots = device.wait(Until.findObject(By.descContains("Show roots")), UI_TIMEOUT_MS);
            assertNotNull("SAF storage drawer", roots);
            roots.click();
            UiObject root = device.findObject(new UiSelector().resourceIdMatches(".*:id/roots_list")
                    .childSelector(new UiSelector().text("LFM test files")));
            assertTrue("SAF image fixture root", root.waitForExists(UI_TIMEOUT_MS));
            root.click();
            device.wait(Until.hasObject(By.res(java.util.regex.Pattern.compile(".*:id/dir_list"))), UI_TIMEOUT_MS);
            new UiScrollable(new UiSelector().resourceIdMatches(".*:id/dir_list"))
                    .scrollIntoView(new UiSelector().descriptionMatches(documentDescription.pattern()));
            file = device.wait(Until.findObject(By.desc(documentDescription)), UI_TIMEOUT_MS);
        }
        assertNotNull("SAF image document should be listed: " + name, file);
        android.graphics.Rect bounds = file.getVisibleBounds();
        device.click(bounds.centerX(), bounds.top + (bounds.height() * 9 / 10));
        device.waitForIdle();
    }
}
