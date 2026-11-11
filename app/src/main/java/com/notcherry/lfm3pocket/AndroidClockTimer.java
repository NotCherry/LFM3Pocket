package com.notcherry.lfm3pocket;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.provider.AlarmClock;

/** Opens the platform clock UI with a timer request; it does not claim the timer was created. */
final class AndroidClockTimer implements TimerController.Launcher {
    private final Context context;

    AndroidClockTimer(Context context) {
        Context applicationContext = null;
        try { if (context != null) applicationContext = context.getApplicationContext(); }
        catch (RuntimeException ignored) { /* A contract test may construct an unattached Application. */ }
        this.context = applicationContext == null ? context : applicationContext;
    }

    @Override public void open(int seconds, String label) {
        if (context == null) throw new IllegalStateException("Android context is unavailable");
        try {
            context.startActivity(createIntent(seconds, label));
        } catch (ActivityNotFoundException e) {
            throw new TimerController.NoClockAppException();
        }
    }

    static Intent createIntent(int seconds, String label) {
        return new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                // Leave UI visible so the user can verify or confirm the timer in the clock app.
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
