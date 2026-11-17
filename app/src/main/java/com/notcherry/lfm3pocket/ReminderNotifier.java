package com.notcherry.lfm3pocket;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/** Posts one reminder notification on the Hanami reminders channel. */
public final class ReminderNotifier {
  private ReminderNotifier() {}

  public static void show(Context context, int notificationId, String title, String body) {
    if (context == null) return;
    Context app = context.getApplicationContext();
    HanamiNotifications.ensureChannels(app);
    String safeTitle = title == null ? "" : title;
    String safeBody = body == null ? "" : body;
    Intent open = new Intent(app, MainActivity.class);
    open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    PendingIntent content = PendingIntent.getActivity(
            app, notificationId, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification.Builder builder = new Notification.Builder(app, HanamiNotifications.REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_runtime)
            .setContentTitle(safeTitle.isEmpty()
                    ? app.getString(R.string.reminder_default_title) : safeTitle)
            .setContentText(safeBody)
            .setStyle(new Notification.BigTextStyle().bigText(safeBody))
            .setAutoCancel(true)
            .setContentIntent(content);
    android.app.NotificationManager manager = app.getSystemService(android.app.NotificationManager.class);
    if (manager == null) return;
    try {
      manager.notify(notificationId, builder.build());
    } catch (SecurityException ignored) {
      // POST_NOTIFICATIONS denied; scheduling should have been gated earlier.
    }
  }
}