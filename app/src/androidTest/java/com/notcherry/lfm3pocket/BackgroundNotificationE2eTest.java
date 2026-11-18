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
    private static final long WAIT = 12000L;
    private TestLfm3PocketApplication app;
    private UiDevice device;
    private String pkg;
    private boolean captureShots;

    @Before public void prepare() throws IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        app = (TestLfm3PocketApplication) context.getApplicationContext();
        app.scriptedEngine().resetForTest();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        pkg = context.getPackageName();
        captureShots = "1".equals(InstrumentationRegistry.getArguments().getString("captureShots"));
        File file = new File(context.getFilesDir(), "background-runtime.gguf");
        TestGgufFixtures.model(file);
        assertTrue(context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
                .edit().clear()
                .putString("model", file.getAbsolutePath())
                .putString("model_name", "Pocket GGUF")
                .putBoolean("search_enabled", false)
                .commit());
    }

    @Test public void loadedModelAndActiveReplyDriveTheOngoingNotification() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_send").enabled(true)), WAIT));
            Notification idle = awaitNotice("Model loaded");
            assertTrue(idle.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
                    .contains("Model loaded"));
            if (captureShots) shootShade("notification_idle.png");

            UiObject2 settings = device.wait(Until.findObject(By.res(pkg, "settings_open").enabled(true)), WAIT);
            if (settings == null) {
                UiObject2 back = device.findObject(By.res(pkg, "settings_back"));
                if (back != null) {
                    back.click();
                    settings = device.wait(Until.findObject(By.res(pkg, "settings_open").enabled(true)), WAIT);
                }
            }
            assertNotNull(settings);
            settings.click();
            assertNotNull("Settings did not open", device.wait(Until.hasObject(By.res(pkg, "settings_back")), WAIT));
            UiObject2 keep = null;
            try {
                UiScrollable scroll = new UiScrollable(new UiSelector().resourceId(pkg + ":id/model_configuration_scroll"));
                scroll.setAsVerticalList();
                scroll.scrollIntoView(new UiSelector().resourceId(pkg + ":id/settings_keep_loaded"));
                keep = device.wait(Until.findObject(By.res(pkg, "settings_keep_loaded")), WAIT);
                if (keep == null) {
                    scroll.scrollIntoView(new UiSelector().resourceId(pkg + ":id/settings_idle_unload"));
                    keep = device.wait(Until.findObject(By.res(pkg, "settings_keep_loaded")), WAIT);
                }
                scroll.scrollIntoView(new UiSelector().resourceId(pkg + ":id/settings_lock_preview"));
            } catch (Exception ignored) {
                keep = device.wait(Until.findObject(By.res(pkg, "settings_keep_loaded")), WAIT);
            }
            assertNotNull("Keep-loaded control missing in settings", keep);
            assertTrue(keep.isChecked());
            assertNotNull("Idle unload control missing in settings",
                    device.wait(Until.findObject(By.res(pkg, "settings_idle_unload")), WAIT));
            assertNotNull("Lock preview control missing in settings",
                    device.wait(Until.findObject(By.res(pkg, "settings_lock_preview")), WAIT));
            if (captureShots) shoot("settings_background.png");

            device.findObject(By.res(pkg, "settings_back")).click();
            app.scriptedEngine().holdFirstToken();
            device.findObject(By.res(pkg, "chat_input")).setText("Hold this reply");
            device.findObject(By.res(pkg, "chat_send")).click();
            assertTrue(app.scriptedEngine().awaitGenerationStarted());
            Notification generating = awaitNotice("Generating");
            String text = String.valueOf(generating.extras.getCharSequence(Notification.EXTRA_TEXT))
                    + "\n" + String.valueOf(generating.extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
            assertTrue("Notification body should mention token progress: " + text, text.contains("token"));
            app.scriptedEngine().releaseFirstToken();
            if (captureShots) {
                SystemClock.sleep(1200);
                shootShade("notification_generating.png");
            }
            device.findObject(By.res(pkg, "chat_stop")).click();
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_send").enabled(true)), WAIT));
            awaitNotice("Model loaded");
        }
    }

    private Notification awaitNotice(String titlePart) throws InterruptedException {
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        long deadline = SystemClock.elapsedRealtime() + WAIT;
        while (SystemClock.elapsedRealtime() < deadline) {
            StatusBarNotification[] active = manager.getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification item : active) {
                    Notification notification = item.getNotification();
                    CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
                    if (title != null && title.toString().contains(titlePart)) return notification;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No ongoing notification titled " + titlePart);
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
