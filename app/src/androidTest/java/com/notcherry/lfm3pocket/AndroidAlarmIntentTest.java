package com.notcherry.lfm3pocket;

import android.content.Intent;
import android.provider.AlarmClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Verifies Android's alarm intent contract without creating a real alarm during CI. */
@RunWith(AndroidJUnit4.class)
public final class AndroidAlarmIntentTest {
    @Test public void createsVisibleSetAlarmIntentWithExactValidatedTimeAndLabel() {
        Intent intent = AndroidClockAlarm.createIntent(8, 15, "Standup");

        assertEquals(AlarmClock.ACTION_SET_ALARM, intent.getAction());
        assertEquals(8, intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1));
        assertEquals(15, intent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1));
        assertEquals("Standup", intent.getStringExtra(AlarmClock.EXTRA_MESSAGE));
        assertFalse(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true));
        assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }
}
