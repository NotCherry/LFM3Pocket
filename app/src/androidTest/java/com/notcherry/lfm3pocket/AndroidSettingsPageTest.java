package com.notcherry.lfm3pocket;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Checks fixed settings actions and opens Wi-Fi settings on the emulator without changing it. */
@RunWith(AndroidJUnit4.class)
public final class AndroidSettingsPageTest {
    @Test public void supportedPagesMapOnlyToTheirDocumentedSettingsActions() {
        assertAction(SettingsPageController.Page.WIFI, Settings.ACTION_WIFI_SETTINGS);
        assertAction(SettingsPageController.Page.BLUETOOTH, Settings.ACTION_BLUETOOTH_SETTINGS);
        assertAction(SettingsPageController.Page.SOUND, Settings.ACTION_SOUND_SETTINGS);
        assertAction(SettingsPageController.Page.DISPLAY, Settings.ACTION_DISPLAY_SETTINGS);
        assertAction(SettingsPageController.Page.APPLICATION, Settings.ACTION_APPLICATION_SETTINGS);
    }

    @Test public void wifiSettingsPageOpensOnEmulatorAndReturnsWithoutChangingSettings() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SettingsPageController controller = new SettingsPageController(new AndroidSettingsLauncher(context));

        AndroidActionExecutor.ActionResult result = controller.open("wifi");

        assertTrue(result.status + ": " + result.message, result.succeeded);
        assertEquals("SETTINGS_OPENED", result.status);
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try {
            assertTrue("Wi-Fi settings should be foregrounded", device.wait(
                    Until.hasObject(By.pkg("com.android.settings")), 5000));
        } finally {
            device.pressBack();
        }
    }

    private static void assertAction(SettingsPageController.Page page, String expectedAction) {
        Intent intent = AndroidSettingsLauncher.createIntent(page);
        assertEquals(expectedAction, intent.getAction());
        assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertTrue("Settings launcher must not smuggle a mutation extra", intent.getExtras() == null);
    }
}
