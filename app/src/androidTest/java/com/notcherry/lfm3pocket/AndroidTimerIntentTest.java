package com.notcherry.lfm3pocket;

import android.content.Intent;
import android.provider.AlarmClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Verifies the platform intent contract without creating a persistent system timer in the test. */
@RunWith(AndroidJUnit4.class)
public final class AndroidTimerIntentTest {
    @Test public void createsVisibleSetTimerIntentWithExactValidatedRequest() {
        Intent intent = AndroidClockTimer.createIntent(75, "Tea break");

        assertEquals(AlarmClock.ACTION_SET_TIMER, intent.getAction());
        assertEquals(75, intent.getIntExtra(AlarmClock.EXTRA_LENGTH, -1));
        assertEquals("Tea break", intent.getStringExtra(AlarmClock.EXTRA_MESSAGE));
        assertFalse(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true));
        assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }
}
