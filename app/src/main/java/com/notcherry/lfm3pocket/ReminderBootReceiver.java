package com.notcherry.lfm3pocket;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-queues persisted reminders after device reboot. */
public final class ReminderBootReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context context, Intent intent) {
    if (context == null || intent == null) return;
    String action = intent.getAction();
    if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
            && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) return;
    AndroidReminderScheduler.rescheduleAll(context);
  }
}