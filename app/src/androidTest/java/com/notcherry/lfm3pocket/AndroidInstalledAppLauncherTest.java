package com.notcherry.lfm3pocket;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises Android's package visibility and launches the emulator's Settings app. */
@RunWith(AndroidJUnit4.class)
public final class AndroidInstalledAppLauncherTest {
    @Test public void explicitLauncherIntentTargetsOnlyTheSelectedPackageAndActivity() {
        Intent intent = AndroidInstalledAppLauncher.createIntent("com.example.notes", "com.example.notes.Home");

        assertEquals(Intent.ACTION_MAIN, intent.getAction());
        assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER));
        assertEquals(new ComponentName("com.example.notes", "com.example.notes.Home"), intent.getComponent());
        assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }

    @Test public void installedSettingsAppLaunchesThroughPackageManagerVisibilityQuery() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstalledAppController controller = new InstalledAppController(new AndroidInstalledAppLauncher(context));
        AndroidActionExecutor.ActionResult result = controller.open("com.android.settings");

        assertTrue(result.status + ": " + result.message, result.succeeded);
        assertEquals("APP_OPENED", result.status);
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try {
            assertTrue("Settings should be foregrounded by the explicit launcher request",
                    device.wait(Until.hasObject(By.pkg("com.android.settings")), 5000));
        } finally {
            device.pressBack();
        }
    }

    @Test public void missingPackageFailsWithoutOpeningAnotherApp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstalledAppController controller = new InstalledAppController(new AndroidInstalledAppLauncher(context));

        AndroidActionExecutor.ActionResult result = controller.open("com.example.definitely.not.installed");

        assertFalse(result.succeeded);
        assertEquals("APP_NOT_FOUND", result.status);
    }
}
