package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.TrafficStats;
import android.os.Process;
import android.provider.Settings;

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
import java.util.Arrays;
import java.util.regex.Pattern;

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
        // The injected fake accepts this metadata-only fixture without ever invoking JNI.
        File file = new File(context.getFilesDir(), "ui-e2e-dummy.gguf");
        TestGgufFixtures.model(file);
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

    @Test public void launchStreamCancelAndRetryRecoversVisibleChatControls() throws Exception {
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            // Real Activity launch, async fake model load, and UI readiness.
            awaitEnabled("chat_send");
            assertTrue(app.scriptedEngine().loadedPath().endsWith("ui-e2e-dummy.gguf"));

            UiObject2 input = device.wait(
                    Until.findObject(By.res(packageName, "chat_input")), UI_TIMEOUT_MS);
            assertNotNull(input);
            input.setText("First prompt");
            app.scriptedEngine().holdFirstToken();
            awaitEnabled("chat_send").click();

            // Progress and mode must be visible before the fake model emits its first token.
            assertTrue(app.scriptedEngine().awaitGenerationStarted());
            UiObject2 status=device.wait(Until.findObject(By.res(packageName,"chat_live_status")
                    .textContains("Generating locally")),UI_TIMEOUT_MS);
            assertNotNull("Local inference status should appear before the first token",status);
            assertTrue(status.getText().contains("offline"));
            app.scriptedEngine().releaseFirstToken();

            // The scripted model now blocks after the first streamed piece.
            awaitTranscript("FAKE_STREAM_BEGIN");
            assertFalse(device.findObject(By.res(packageName, "chat_send")).isEnabled());
            awaitEnabled("chat_stop").click();

            // stop() releases the model; UI must recover before the next turn.
            awaitEnabled("chat_send");
            assertNotNull(device.wait(Until.findObject(By.res(packageName,"chat_live_status")
                    .textContains("Response stopped")),UI_TIMEOUT_MS));
            awaitTranscript("FAKE_STREAM_BEGIN");
            UiObject2 retryInput = device.findObject(By.res(packageName, "chat_input"));
            assertNotNull(retryInput);
            retryInput.setText("Retry prompt");
            awaitEnabled("chat_send").click();

            UiObject2 transcript = awaitTranscript("FAKE_RETRY_COMPLETE");
            assertNotNull(device.wait(Until.findObject(By.res(packageName,"chat_live_status")
                    .textContains("Response complete · local model")),UI_TIMEOUT_MS));
            assertTrue(transcript.getText().contains("First prompt"));
            assertTrue(transcript.getText().contains("Retry prompt"));
            assertEquals(2, app.scriptedEngine().callCount());
            assertTrue(app.scriptedEngine().lastPrompt().contains("First prompt"));
            assertTrue(app.scriptedEngine().lastPrompt().contains("FAKE_STREAM_BEGIN"));
            assertTrue(app.scriptedEngine().lastPrompt().contains("Retry prompt"));
            assertEquals("Cancelled partial response must appear exactly once", 1,
                    transcript.getText().split("FAKE_STREAM_BEGIN", -1).length - 1);
            awaitEnabled("chat_send");
            assertFalse(device.findObject(By.res(packageName, "chat_stop")).isEnabled());
        }
    }
    @Test public void inferenceFailureIsVisibleAndRestoresSendControl() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            awaitEnabled("chat_send");
            app.scriptedEngine().failNextGeneration();
            UiObject2 input=device.findObject(By.res(packageName,"chat_input"));
            assertNotNull(input);input.setText("Fail visibly");awaitEnabled("chat_send").click();
            UiObject2 recovered=device.wait(Until.findObject(By.res(packageName,"chat_send").enabled(true)),UI_TIMEOUT_MS);
            assertNotNull("Send must recover after failed inference",recovered);
            assertEquals(1,app.scriptedEngine().callCount());
            UiObject2 failure=device.wait(Until.findObject(By.res(packageName,"chat_live_status")
                    .textContains("Response failed")),UI_TIMEOUT_MS);
            assertNotNull("Generation failure should be shown in the chat",failure);
            assertTrue(failure.getText().contains("offline"));
            awaitEnabled("chat_send");
        }
    }
    @Test public void airplaneModeTextChatDoesNotTransmitNetworkBytes() throws Exception {
        String previousAirplane = device.executeShellCommand(
                "settings get global airplane_mode_on").trim();
        try {
            // Android emulator: disable radio-backed networks. Some emulator builds
            // retain virtual Ethernet; per-UID TX counters separately verify app isolation.
            device.executeShellCommand("settings put global airplane_mode_on 1");
            device.executeShellCommand(
                    "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true");
            device.executeShellCommand("svc wifi disable");
            device.executeShellCommand("svc data disable");
            assertEquals(1, Settings.Global.getInt(app.getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0));

            try (ActivityScenario<MainActivity> scenario =
                         ActivityScenario.launch(MainActivity.class)) {
                awaitEnabled("chat_send");
                long uidTxBefore = TrafficStats.getUidTxBytes(Process.myUid());
                assertTrue("UID network accounting must be available for offline assertion",
                        uidTxBefore >= 0);

                UiObject2 input = device.findObject(By.res(packageName, "chat_input"));
                assertNotNull(input);
                input.setText("Answer without internet");
                awaitEnabled("chat_send").click();
                awaitTranscript("FAKE_STREAM_BEGIN");
                awaitEnabled("chat_stop").click();
                awaitEnabled("chat_send");
                assertEquals("Offline core chat must not transmit data for this app UID",
                        uidTxBefore, TrafficStats.getUidTxBytes(Process.myUid()));
            }
        } finally {
            String restored = "1".equals(previousAirplane) ? "1" : "0";
            device.executeShellCommand("settings put global airplane_mode_on " + restored);
            device.executeShellCommand(
                    "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state " +
                            ("1".equals(restored) ? "true" : "false"));
            if ("0".equals(restored)) {
                device.executeShellCommand("svc wifi enable");
                device.executeShellCommand("svc data enable");
            }
        }
    }

    @Test public void textChatDoesNotRequestCameraPermissionAndSurvivesPermissionDenial()
            throws Exception {
        // Declaring CAMERA supports explicit flashlight use. Text chat must not
        // trigger the runtime dialog or require that permission.
        String[] requested = app.getPackageManager()
                .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions;
        assertTrue("Flashlight needs CAMERA declared in the manifest", requested != null &&
                Arrays.asList(requested).contains(Manifest.permission.CAMERA));
        assertEquals(PackageManager.PERMISSION_DENIED,
                app.checkSelfPermission(Manifest.permission.CAMERA));
        assertEquals(PackageManager.PERMISSION_DENIED,
                app.checkSelfPermission(Manifest.permission.RECORD_AUDIO));

        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            awaitEnabled("chat_send");
            awaitEnabled("voice_record").click();

            // Android API 30's permission controller uses "Don't allow"; accept
            // the standard "Deny" label too, without depending on its package name.
            UiObject2 deny = device.wait(Until.findObject(By.text(
                    Pattern.compile("(?i)(.*don.t allow.*|.*deny.*)"))), UI_TIMEOUT_MS);
            assertNotNull("Microphone permission denial action was not shown", deny);
            deny.click();
            assertNotNull("Expected microphone denied status on actual Activity",
                    device.wait(Until.findObject(By.res(packageName, "chat_status")
                            .textContains("Microphone access is needed")), UI_TIMEOUT_MS));

            UiObject2 input = device.findObject(By.res(packageName, "chat_input"));
            assertNotNull(input);
            input.setText("Text is still available");
            awaitEnabled("chat_send").click();
            awaitTranscript("FAKE_STREAM_BEGIN");
            awaitEnabled("chat_stop").click();
            awaitEnabled("chat_send");
            assertEquals("Text chat must not request camera access for flashlight use",
                    PackageManager.PERMISSION_DENIED,
                    app.checkSelfPermission(Manifest.permission.CAMERA));
            assertEquals(PackageManager.PERMISSION_DENIED,
                    app.checkSelfPermission(Manifest.permission.RECORD_AUDIO));
        }
    }

    @Test public void recreateDuringGenerationStartsAnIndependentSession() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitEnabled("chat_send");
            device.findObject(By.res(packageName, "chat_input")).setText("Old activity turn");
            awaitEnabled("chat_send").click();
            awaitTranscript("FAKE_STREAM_BEGIN");
            scenario.recreate();
            awaitEnabled("chat_send");
            assertFalse(device.findObject(By.res(packageName, "chat_transcript")).getText()
                    .contains("Old activity turn"));
            device.findObject(By.res(packageName, "chat_input")).setText("New activity turn");
            awaitEnabled("chat_send").click();
            awaitTranscript("FAKE_STREAM_BEGIN");
            awaitEnabled("chat_stop").click();
            awaitEnabled("chat_send");
            assertEquals(1, app.scriptedEngine().callCount());
        }
    }

    @Test public void clearingAnActiveTurnDoesNotResurrectStaleTokens() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitEnabled("chat_send");
            device.findObject(By.res(packageName, "chat_input")).setText("Discard this turn");
            awaitEnabled("chat_send").click();
            awaitTranscript("FAKE_STREAM_BEGIN");
            device.findObject(By.text("Clear")).click();
            awaitEnabled("chat_send");
            UiObject2 transcript = awaitTranscript("No conversation yet.");
            assertFalse(transcript.getText().contains("FAKE_STREAM_BEGIN"));
            assertFalse(transcript.getText().contains("Discard this turn"));
        }
    }

}
