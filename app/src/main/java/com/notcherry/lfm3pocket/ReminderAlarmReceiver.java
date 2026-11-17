package com.notcherry.lfm3pocket;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires an exact alarm reminder and removes the persisted entry. */
public final class ReminderAlarmReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context context, Intent intent) {
    if (context == null || intent == null) return;
    if (!AndroidReminderScheduler.ACTION_FIRE.equals(intent.getAction())) return;
    String id = intent.getStringExtra(AndroidReminderScheduler.EXTRA_ID);
    String title = intent.getStringExtra(ReminderNotifyWorker.KEY_TITLE);
    String body = intent.getStringExtra(ReminderNotifyWorker.KEY_BODY);
    int notificationId = intent.getIntExtra(ReminderNotifyWorker.KEY_NOTIFICATION_ID, 0);
    if (notificationId == 0 && id != null) {
      ReminderStore.Entry entry = new ReminderStore(context).findById(id);
      if (entry != null) notificationId = entry.notificationId;
    }
    if (notificationId == 0) notificationId = ReminderIds.notificationId(id == null ? "reminder" : id);
    ReminderNotifier.show(context, notificationId, title, body);
    if (id != null) new ReminderStore(context).remove(id);
  }
}