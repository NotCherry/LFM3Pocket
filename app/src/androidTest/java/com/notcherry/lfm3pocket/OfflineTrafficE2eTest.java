package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
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
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;

/** Isolated system-mutating offline proof: airplane/radio toggles with strict restoration. */
@RunWith(AndroidJUnit4.class)
public final class OfflineTrafficE2eTest {
    private static final long UI_TIMEOUT_MS = 12000L;
    private TestLfm3PocketApplication app;
    private UiDevice device;
    private String packageName;
    private String previousAirplane;

    @Before public void prepareFakeModel() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        app = (TestLfm3PocketApplication) context.getApplicationContext();
        app.scriptedEngine().resetForTest();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        packageName = context.getPackageName();
        previousAirplane = device.executeShellCommand(
                "settings get global airplane_mode_on").trim();

        File file = new File(context.getFilesDir(), "ui-e2e-dummy.gguf");
        TestGgufFixtures.model(file);
        assertTrue(context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
                .edit().clear()
                .putString("model", file.getAbsolutePath())
                .putBoolean("search_enabled", false)
                .commit());
    }

    @After public void restoreNetworkState() {
        String restored = "1".equals(previousAirplane) ? "1" : "0";
        try { device.executeShellCommand("settings put global airplane_mode_on " + restored); } catch (Exception ignored) { }
        try {
            device.executeShellCommand("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state "
                    + ("1".equals(restored) ? "true" : "false"));
        } catch (Exception ignored) { }
        try {
            device.executeShellCommand("svc wifi " + ("1".equals(restored) ? "disable" : "enable"));
            device.executeShellCommand("svc data " + ("1".equals(restored) ? "disable" : "enable"));
        } catch (Exception ignored) { }
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

    @Test public void airplaneModeTextChatDoesNotTransmitNetworkBytes() throws Exception {
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
    }
}
