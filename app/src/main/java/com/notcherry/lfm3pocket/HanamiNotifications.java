package com.notcherry.lfm3pocket;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

/**
 * The only place that creates Hanami notification channels.
 *
 * <p>Channel id {@code model_runtime} is shared with the foreground service.
 * The shade name is "Hanami model". A later approval channel should be named
 * "Hanami approval".
 */
public final class HanamiNotifications {
    public static final String MODEL_RUNTIME_CHANNEL_ID = "model_runtime";
    public static final String REMINDER_CHANNEL_ID = "reminders";

    private HanamiNotifications() {}

    public static void ensureChannels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                MODEL_RUNTIME_CHANNEL_ID,
                context.getString(R.string.notification_channel_model_runtime),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(context.getString(R.string.notification_channel_model_runtime_desc));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);

        NotificationChannel reminders = new NotificationChannel(
                REMINDER_CHANNEL_ID,
                context.getString(R.string.notification_channel_reminders),
                NotificationManager.IMPORTANCE_DEFAULT);
        reminders.setDescription(context.getString(R.string.notification_channel_reminders_desc));
        manager.createNotificationChannel(reminders);
    }
}
