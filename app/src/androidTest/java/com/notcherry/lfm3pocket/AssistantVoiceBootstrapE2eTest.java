package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Verifies the assistant bootstrap intent contract: system assist entry requests a fresh chat and
 * a Hanami voice session through the single MainActivity path (routed by AssistEntryActivity).
 */
@RunWith(AndroidJUnit4.class)
public final class AssistantVoiceBootstrapE2eTest {
    @Test public void assistantBootstrapIntentRequestsFreshChatAndVoiceSession() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(context, MainActivity.class)
                .putExtra(MainActivity.EXTRA_FRESH_CHAT, true)
                .putExtra(MainActivity.EXTRA_ASSISTANT_VOICE_SESSION, true);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                assertTrue("Assistant bootstrap must request a fresh chat",
                        activity.freshChatRequestedFromAssist);
                assertTrue("Assistant bootstrap must request a Hanami voice session",
                        activity.assistantVoiceSessionLaunchRequested);
            });
        }
    }
}
