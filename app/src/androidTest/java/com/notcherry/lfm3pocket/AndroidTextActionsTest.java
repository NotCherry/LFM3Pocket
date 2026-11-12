package com.notcherry.lfm3pocket;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Verifies exact clipboard content and that sharing opens the chooser without selecting a target. */
@RunWith(AndroidJUnit4.class)
public final class AndroidTextActionsTest {
    @Test public void copyWritesTheExactRequestedTextToAndroidClipboard() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String exact = "  exact clipboard text\nsecond line 🙂  ";
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                AndroidActionExecutor.ActionResult result = controller(activity).copy(exact);
                assertTrue(result.status + ": " + result.message, result.succeeded);
                ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                assertNotNull(clipboard);
                assertNotNull(clipboard.getPrimaryClip());
                assertEquals(exact, clipboard.getPrimaryClip().getItemAt(0).coerceToText(activity).toString());
            });
        }
    }

    @Test public void shareOpensAndroidChooserWithExactTextAndDoesNotSendIt() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String exact = "Share only after target selection";
        Intent chooser = AndroidTextActions.createChooserIntent(exact);
        assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
        Intent send = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        assertNotNull(send);
        assertEquals(Intent.ACTION_SEND, send.getAction());
        assertEquals("text/plain", send.getType());
        assertEquals(exact, send.getStringExtra(Intent.EXTRA_TEXT));
        assertTrue((chooser.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);

        AndroidActionExecutor.ActionResult result = controller(context).share(exact);

        assertTrue(result.status + ": " + result.message, result.succeeded);
        assertEquals("SHARE_SHEET_OPENED", result.status);
        assertTrue(result.message.contains("nothing has been sent yet"));
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try {
            assertTrue("Android's resolver chooser should be foregrounded",
                    device.wait(Until.hasObject(By.pkg("android")), 5000));
        } finally {
            device.pressBack();
        }
    }

    private static TextActionController controller(Context context) {
        AndroidTextActions actions = new AndroidTextActions(context);
        return new TextActionController(actions, actions);
    }
}
