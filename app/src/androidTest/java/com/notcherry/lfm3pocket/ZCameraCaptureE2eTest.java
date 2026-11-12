package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;

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
import java.util.regex.Pattern;

/** Real permission and CameraX capture journey on the configured Android emulator. */
@RunWith(AndroidJUnit4.class)
public final class ZCameraCaptureE2eTest {
    private static final long UI_TIMEOUT_MS = 20000L;
    private UiDevice device;
    private String packageName;

    @Before public void prepareFakeModel() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        packageName = context.getPackageName();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        File file = new File(context.getFilesDir(), "camera-e2e-dummy.gguf");
        TestGgufFixtures.model(file);
        assertTrue(context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
                .edit().clear().putString("model", file.getAbsolutePath())
                .putBoolean("search_enabled", false).commit());
    }

    private UiObject2 waitFor(String id) {
        UiObject2 object = device.wait(Until.findObject(By.res(packageName, id)), UI_TIMEOUT_MS);
        assertNotNull("Expected UI element: " + id, object);
        return object;
    }

    private UiObject2 waitForButton(String id) {
        UiObject2 object = device.wait(Until.findObject(By.res(packageName, id).enabled(true)), UI_TIMEOUT_MS);
        assertNotNull("Expected enabled UI control: " + id, object);
        return object;
    }

    private UiObject2 answerCameraPermission(String labelPattern) {
        UiObject2 choice = device.wait(Until.findObject(By.text(Pattern.compile(labelPattern))), UI_TIMEOUT_MS);
        assertNotNull("Camera runtime permission choice was not shown", choice);
        return choice;
    }

    @Test public void cameraCapturesFullSizePrivateStillAndReleasesLifecycle() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            waitForButton("chat_camera").click();
            if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                answerCameraPermission("(?i)(while using the app|only this time|allow)").click();
            }

            waitFor("camera_preview");
            assertNotNull("CameraX must report a ready preview before capture",
                    device.wait(Until.findObject(By.res(packageName, "camera_status")
                            .textContains("Camera ready")), UI_TIMEOUT_MS));
            waitForButton("camera_capture").click();
            assertNotNull("Captured image should attach to the pending chat turn",
                    device.wait(Until.findObject(By.res(packageName, "chat_status")
                            .textContains("photo attached")), UI_TIMEOUT_MS));

            final String[] path = new String[1];
            scenario.onActivity(activity -> path[0] = activity.pendingMediaPathForTest());
            assertNotNull(path[0]);
            File photo = new File(path[0]);
            assertTrue("CameraX output must remain in private app cache", photo.getCanonicalPath()
                    .startsWith(context.getCacheDir().getCanonicalPath() + File.separator));
            assertTrue("Full-size camera output should not be the ACTION_IMAGE_CAPTURE thumbnail",
                    photo.length() >= 1024);
            BitmapFactory.Options dimensions = new BitmapFactory.Options();
            dimensions.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(photo.getAbsolutePath(), dimensions);
            assertTrue("Expected full-size camera still, got " + dimensions.outWidth + "x" + dimensions.outHeight,
                    dimensions.outWidth >= 320 && dimensions.outHeight >= 240);
            assertTrue("Explicitly granted camera permission should remain available for the capture flow",
                    context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED);
            photo.delete();
            waitForButton("chat_camera").click();
            assertNotNull("Camera must be released and able to bind again after the first capture",
                    device.wait(Until.findObject(By.res(packageName, "camera_status")
                            .textContains("Camera ready")), UI_TIMEOUT_MS));
            waitForButton("camera_cancel").click();
            assertNotNull("Closing the camera activity must return to chat", device.wait(
                    Until.findObject(By.res(packageName, "chat_camera")), UI_TIMEOUT_MS));
        }
    }
}
