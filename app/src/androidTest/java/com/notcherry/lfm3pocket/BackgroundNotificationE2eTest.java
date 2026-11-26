package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.UiScrollable;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;

/** Foreground-service notification follows the resident model and an in-flight reply. */
@RunWith(AndroidJUnit4.class)
public final class BackgroundNotificationE2eTest {
    private static final long UI_WAIT = 20000L;
    private static final long NOTICE_WAIT = 30000L;
    private TestLfm3PocketApplication app;
    private UiDevice device;
    private String pkg;
    private boolean captureShots;

    @Before public void prepare() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        app = (TestLfm3PocketApplication) context.getApplicationContext();
        pkg = context.getPackageName();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        captureShots = "1".equals(InstrumentationRegistry.getArguments().getString("captureShots"));
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancelAll();
        app.scriptedEngine().resetForTest();
        // resetForTest discards the runtime; clear persisted UI state and seed a fake model.
        File file = new File(context.getFilesDir(), "background-runtime.gguf");
        TestGgufFixtures.model(file);
        assertTrue(context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
                .edit().clear()
                .putString("model", file.getAbsolutePath())
                .putString("model_name", "Pocket GGUF")
                .putBoolean("search_enabled", false)
                .commit());
        device.waitForIdle(500);
    }

    @Test public void loadedModelAndActiveReplyDriveTheOngoingNotification() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            UiObject2 send = device.wait(Until.findObject(By.res(pkg, "chat_send").enabled(true)), UI_WAIT);
            assertNotNull("Chat send control never became enabled; " + runtimeState(), send);
            Notification idle = awaitNotice("Model loaded");
            assertTrue("Expected idle notification text to mention Model loaded: " + describe(idle),
                    describe(idle).contains("Model loaded"));
            if (captureShots) shootShade("notification_idle.png");

            SettingsScrollHelper.openSettings(device, pkg, UI_WAIT);
            assertNotNull("Settings did not open", device.wait(Until.hasObject(By.res(pkg, "settings_back")), UI_WAIT));
            SettingsScrollHelper.scrollToViewId(scenario, device, R.id.settings_keep_loaded);
            UiObject2 keep = device.wait(Until.findObject(By.res(pkg, "settings_keep_loaded")), UI_WAIT);
            assertNotNull("Keep-loaded control missing in settings", keep);
            assertTrue("Keep-loaded should be enabled by default", keep.isChecked());
            SettingsScrollHelper.scrollToViewId(scenario, device, R.id.settings_idle_unload);
            assertNotNull("Idle unload control missing in settings",
                    device.wait(Until.findObject(By.res(pkg, "settings_idle_unload")), UI_WAIT));
            SettingsScrollHelper.scrollToViewId(scenario, device, R.id.settings_lock_preview);
            assertNotNull("Lock preview control missing in settings",
                    device.wait(Until.findObject(By.res(pkg, "settings_lock_preview")), UI_WAIT));
            if (captureShots) shoot("settings_background.png");

            UiObject2 back = device.wait(Until.findObject(By.res(pkg, "settings_back").enabled(true)), UI_WAIT);
            assertNotNull("Settings back control missing", back);
            back.click();
            assertNotNull("Chat did not return after closing settings",
                    device.wait(Until.findObject(By.res(pkg, "chat_input")), UI_WAIT));

            app.scriptedEngine().holdFirstToken();
            UiObject2 input = device.wait(Until.findObject(By.res(pkg, "chat_input")), UI_WAIT);
            assertNotNull("Chat input missing before generation", input);
            input.setText("Hold this reply");
            UiObject2 sendNow = device.wait(Until.findObject(By.res(pkg, "chat_send").enabled(true)), UI_WAIT);
            assertNotNull("Chat send not enabled before generation", sendNow);
            sendNow.click();
            assertTrue("Scripted generation did not start", app.scriptedEngine().awaitGenerationStarted());
            UiObject2 stop = device.wait(Until.findObject(By.res(pkg, "chat_stop").enabled(true)), UI_WAIT);
            assertNotNull("Stop control did not appear during generation", stop);
            Notification generating = awaitNotice("Generating");
            String text = describe(generating);
            assertTrue("Notification body should mention token progress: " + text + " | " + runtimeState(),
                    text.contains("token"));
            app.scriptedEngine().releaseFirstToken();
            if (captureShots) {
                SystemClock.sleep(1200);
                shootShade("notification_generating.png");
            }
            stop = device.wait(Until.findObject(By.res(pkg, "chat_stop").enabled(true)), UI_WAIT);
            assertNotNull("Stop control missing before stop click", stop);
            stop.click();
            assertNotNull("Chat send not re-enabled after stop; " + runtimeState(),
                    device.wait(Until.findObject(By.res(pkg, "chat_send").enabled(true)), UI_WAIT));
            awaitNotice("Model loaded");
        }
    }

    private Notification awaitNotice(String titlePart) throws InterruptedException {
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        long deadline = SystemClock.elapsedRealtime() + NOTICE_WAIT;
        while (SystemClock.elapsedRealtime() < deadline) {
            StatusBarNotification[] active = manager == null ? null : manager.getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification item : active) {
                    Notification notification = item.getNotification();
                    CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
                    if (title != null && title.toString().contains(titlePart)) return notification;
                }
            }
            Thread.sleep(150);
        }
        throw new AssertionError("No active notification titled containing '" + titlePart + "'. "
                + "active=" + describeActiveNotifications(manager) + " | " + runtimeState());
    }

    private String describeActiveNotifications(NotificationManager manager) {
        if (manager == null) return "<no NotificationManager>";
        StatusBarNotification[] active = manager.getActiveNotifications();
        if (active == null || active.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < active.length; i++) {
            if (i > 0) sb.append("; ");
            sb.append(describe(active[i].getNotification()));
        }
        sb.append("]");
        return sb.toString();
    }

    private String describe(Notification notification) {
        if (notification == null) return "<null>";
        CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence big = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        return "{title=" + title + ", text=" + text + ", big=" + big + "}";
    }

    private String runtimeState() {
        try {
            ModelRuntime runtime = app.modelRuntime();
            RuntimeNotice notice = runtime.currentNotice();
            return "runtime[loaded=" + (runtime.handle() != 0L)
                    + ", generating=" + runtime.isGenerating()
                    + ", notice=" + (notice == null ? "<null>" : (notice.kind + ":" + notice.title + ":" + notice.text))
                    + "]";
        } catch (Throwable t) {
            return "runtime[unavailable:" + t.getClass().getSimpleName() + "]";
        }
    }

    private void shootShade(String name) {
        device.openNotification();
        device.wait(Until.hasObject(By.textContains("Model")), 4000);
        SystemClock.sleep(600);
        shoot(name);
        device.pressBack();
        device.wait(Until.findObject(By.res(pkg, "chat_input")), 4000);
    }

    private void shoot(String name) {
        File dir = app.getFilesDir();
        device.takeScreenshot(new File(dir, name));
    }
}
