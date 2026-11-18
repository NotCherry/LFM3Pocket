package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Verifies the system-assistant entry boots through the single AssistEntryActivity path. */
@RunWith(AndroidJUnit4.class)
public final class AssistantVoiceBootstrapE2eTest {
    @Test public void assistEntryRequestsFreshChatVoiceSessionInMainActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor monitor =
                instrumentation.addMonitor(MainActivity.class.getName(), null, false);
        Intent intent = new Intent(instrumentation.getTargetContext(), AssistEntryActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        instrumentation.startActivitySync(intent);
        Activity main = instrumentation.waitForMonitorWithTimeout(monitor, 15000L);
        try {
            assertNotNull("AssistEntryActivity must route to MainActivity", main);
            Intent launched = main.getIntent();
            assertNotNull(launched);
            assertTrue("Assistant entry must request a fresh chat",
                    launched.getBooleanExtra(MainActivity.EXTRA_FRESH_CHAT, false));
            assertTrue("Assistant entry must request a Hanami voice session",
                    launched.getBooleanExtra(MainActivity.EXTRA_ASSISTANT_VOICE_SESSION, false));
        } finally {
            instrumentation.removeMonitor(monitor);
            if (main != null) main.finish();
        }
    }
}
