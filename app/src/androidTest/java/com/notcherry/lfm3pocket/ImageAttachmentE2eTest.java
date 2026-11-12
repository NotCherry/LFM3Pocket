package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

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

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/** Real SAF-to-chat UI journey for image preview, replacement, removal and invalid data. */
@RunWith(AndroidJUnit4.class)
public final class ImageAttachmentE2eTest {
    private static final long WAIT = 15000L;

    @Test public void attachReplaceRemoveAndRejectInvalidImageWithoutStalePath() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        TestLfm3PocketApplication app = (TestLfm3PocketApplication) context.getApplicationContext();
        app.scriptedEngine().resetForTest();
        File model = new File(context.getFilesDir(), "image-attachment-e2e.gguf");
        TestGgufFixtures.model(model);
        assertTrue(context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().clear()
                .putString("model", model.getAbsolutePath()).putBoolean("search_enabled", false).commit());

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        String packageName = context.getPackageName();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            assertNotNull("Chat should be ready before opening the image picker", device.wait(
                    Until.findObject(By.res(packageName, "chat_send").enabled(true)), WAIT));

            chooseImage(device, packageName, "valid-photo.jpg");
            UiObject2 preview = device.wait(Until.findObject(By.res(packageName, "image_preview")), WAIT);
            assertNotNull("Selected image should render an attachment preview", preview);
            final String[] originalPath = new String[1];
            scenario.onActivity(activity -> originalPath[0] = activity.pendingMediaPathForTest());
            assertNotNull(originalPath[0]);
            assertTrue(new File(originalPath[0]).isFile());

            chooseImage(device, packageName, "replacement-photo.jpg");
            assertNotNull("Replacement image should render its preview", device.wait(
                    Until.findObject(By.res(packageName, "image_preview")), WAIT));
            final String[] replacementPath = new String[1];
            scenario.onActivity(activity -> replacementPath[0] = activity.pendingMediaPathForTest());
            assertNotNull(replacementPath[0]);
            assertNotEquals("Replacement must point to a new private file", originalPath[0], replacementPath[0]);
            assertFalse("Replacing an image must delete the previous temporary file", new File(originalPath[0]).exists());

            UiObject2 remove = device.wait(Until.findObject(By.res(packageName, "image_remove")), WAIT);
            assertNotNull(remove);
            remove.click();
            assertNotNull(device.wait(Until.findObject(By.res(packageName, "chat_status")
                    .textContains("Photo removed")), WAIT));
            assertEquals("Removal must clear the path consumed by the inference session", "",
                    currentPendingPath(scenario));
            assertFalse("Removing must clean the replacement image file", new File(replacementPath[0]).exists());
            assertEquals(null, device.wait(Until.findObject(By.res(packageName, "image_attachment")), 1000));

            chooseImage(device, packageName, "invalid-photo.jpg");
            assertNotNull("Unreadable image data must be rejected in chat", device.wait(
                    Until.findObject(By.res(packageName, "chat_status")
                            .textContains("Photo import failed: Selected file is not a readable image")), WAIT));
            assertEquals("Invalid selection must not leave a stale path", "", currentPendingPath(scenario));
            assertEquals(null, device.wait(Until.findObject(By.res(packageName, "image_attachment")), 1000));
        }
    }

    private static String currentPendingPath(ActivityScenario<MainActivity> scenario) {
        final String[] path = new String[1];
        scenario.onActivity(activity -> path[0] = activity.pendingMediaPathForTest());
        return path[0];
    }

    private static void chooseImage(UiDevice device, String packageName, String name) throws Exception {
        UiObject2 photoButton = device.wait(Until.findObject(By.res(packageName, "chat_photo").enabled(true)), WAIT);
        assertNotNull("Photo picker button should be available", photoButton);
        photoButton.click();

        java.util.regex.Pattern documentDescription = java.util.regex.Pattern.compile(
                "^" + java.util.regex.Pattern.quote(name) + ",.*");
        UiObject2 file = device.wait(Until.findObject(By.desc(documentDescription)), 1500);
        if (file == null) {
            UiObject2 roots = device.wait(Until.findObject(By.descContains("Show roots")), WAIT);
            assertNotNull("SAF storage drawer", roots);
            roots.click();
            UiObject root = device.findObject(new UiSelector().resourceIdMatches(".*:id/roots_list")
                    .childSelector(new UiSelector().text("LFM test files")));
            assertTrue("SAF image fixture root", root.waitForExists(WAIT));
            root.click();
            device.wait(Until.hasObject(By.res(java.util.regex.Pattern.compile(".*:id/dir_list"))), WAIT);
            new UiScrollable(new UiSelector().resourceIdMatches(".*:id/dir_list"))
                    .scrollIntoView(new UiSelector().descriptionMatches(documentDescription.pattern()));
            file = device.wait(Until.findObject(By.desc(documentDescription)), WAIT);
        }
        if (file == null) {
            java.io.ByteArrayOutputStream hierarchy = new java.io.ByteArrayOutputStream();
            device.dumpWindowHierarchy(hierarchy);
            org.junit.Assert.fail("SAF image document should be listed: " + name + ". "
                    + hierarchy.toString("UTF-8"));
        }
        file.click();
    }
}
