package com.notcherry.lfm3pocket;

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
 * Black-box Compose navigation smoke, running with the same injected fake model.
 * The legacy Java chat still handles all model lifecycle and JNI production work.
 */
@RunWith(AndroidJUnit4.class)
public final class ComposeHostE2eTest {
    private static final long TIMEOUT_MS = 12000L;
    private UiDevice device;
    private String packageName;
    private TestLfm3PocketApplication app;

    @Before public void prepare() throws IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        packageName = context.getPackageName();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        app = (TestLfm3PocketApplication) context.getApplicationContext();
        app.scriptedEngine().resetForTest();

        // The real Java MainActivity restores the chosen model path on startup.
        // Injected fake never reads these dummy bytes or loads JNI.
        File path = new File(context.getFilesDir(), "compose-host-e2e.gguf");
        try (FileOutputStream output = new FileOutputStream(path)) {
            output.write(new byte[] { 'G', 'G', 'U', 'F' });
        }
        assertTrue(context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
                .edit().clear().putString("model", path.getAbsolutePath())
                .putBoolean("search_enabled", false).commit());
    }

    @Test public void launcherNavigationOpensExistingLegacyChatWithInjectedModel() {
        try (ActivityScenario<ComposeHostActivity> scenario =
                     ActivityScenario.launch(ComposeHostActivity.class)) {
            UiObject2 open = device.wait(
                    Until.findObject(By.text("Open chat")), TIMEOUT_MS);
            assertNotNull("Compose home must expose Open chat", open);
            open.click();

            UiObject2 send = device.wait(Until.findObject(
                    By.res(packageName, "chat_send").enabled(true)), TIMEOUT_MS);
            assertNotNull("Existing Java chat must become usable", send);
            assertTrue("Expected existing Java Activity to use injected fake model",
                    app.scriptedEngine().loadedPath().endsWith("compose-host-e2e.gguf"));
            UiObject2 input = device.findObject(By.res(packageName, "chat_input"));
            assertNotNull(input);
            input.setText("Compose navigation test");
            send.click();
            assertNotNull("Existing chat must stream via test inference engine",
                    device.wait(Until.findObject(
                            By.res(packageName, "chat_transcript")
                              .textContains("FAKE_STREAM_BEGIN")), TIMEOUT_MS));
            device.wait(Until.findObject(
                    By.res(packageName, "chat_stop").enabled(true)), TIMEOUT_MS).click();
        }
    }

    @Test public void composeAboutRouteCanReturnHome() {
        try (ActivityScenario<ComposeHostActivity> scenario =
                     ActivityScenario.launch(ComposeHostActivity.class)) {
            UiObject2 about = device.wait(
                    Until.findObject(By.text("About offline mode")), TIMEOUT_MS);
            assertNotNull(about);
            about.click();
            UiObject2 back = device.wait(
                    Until.findObject(By.text("Back to home")), TIMEOUT_MS);
            assertNotNull("Compose navigation route was not displayed", back);
            back.click();
            assertNotNull("Compose back stack did not return to home",
                    device.wait(Until.findObject(By.text("Open chat")), TIMEOUT_MS));
        }
    }
}
