package com.notcherry.lfm3pocket;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.provider.AlarmClock;

import java.util.ArrayList;
import java.util.List;

/** Requests a persistent alarm through Android's clock UI after app-level confirmation. */
final class AndroidClockAlarm implements AlarmController.Launcher {
    private final Context context;

    AndroidClockAlarm(Context context) {
        Context applicationContext = null;
        try { if (context != null) applicationContext = context.getApplicationContext(); }
        catch (RuntimeException ignored) { /* A contract test may construct an unattached Application. */ }
        this.context = applicationContext == null ? context : applicationContext;
    }

    @Override public void open(int hour, int minute, String label, List<Integer> days) {
        if (context == null) throw new IllegalStateException("Android context is unavailable");
        try {
            context.startActivity(createIntent(hour, minute, label, days));
        } catch (ActivityNotFoundException e) {
            throw new AlarmController.NoClockAppException();
        }
    }

    @Override public void showAlarms() {
        if (context == null) throw new IllegalStateException("Android context is unavailable");
        try {
            context.startActivity(createShowIntent());
        } catch (ActivityNotFoundException e) {
            throw new AlarmController.NoClockAppException();
        }
    }

    static Intent createIntent(int hour, int minute, String label, List<Integer> days) {
        Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                // The clock can display its UI; the app-level consent was already required.
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (days != null && !days.isEmpty()) {
            intent.putExtra(AlarmClock.EXTRA_DAYS, new ArrayList<>(days));
        }
        return intent;
    }

    static Intent createShowIntent() {
        return new Intent(AlarmClock.ACTION_SHOW_ALARMS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
