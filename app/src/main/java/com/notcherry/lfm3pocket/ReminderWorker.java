package com.notcherry.lfm3pocket;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Posts a scheduled reminder notification. Runs via WorkManager so it survives process death. */
public final class ReminderWorker extends Worker {
    public static final String KEY_WHEN_EPOCH_SEC = "when_epoch_sec";
    public static final String KEY_TITLE = "title";
    public static final String KEY_BODY = "body";

    public ReminderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        HanamiNotifications.ensureChannels(context);
        String title = getInputData().getString(KEY_TITLE);
        String body = getInputData().getString(KEY_BODY);
        if (title == null) title = "";
        if (body == null) body = "";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, HanamiNotifications.REMINDER_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_runtime)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        try {
            NotificationManagerCompat.from(context).notify(notificationId(), builder.build());
            return Result.success();
        } catch (SecurityException e) {
            // Notification permission may have been revoked after scheduling.
            return Result.failure();
        } catch (RuntimeException e) {
            return Result.failure();
        }
    }

    private int notificationId() {
        long when = getInputData().getLong(KEY_WHEN_EPOCH_SEC, 0L);
        String title = getInputData().getString(KEY_TITLE);
        String body = getInputData().getString(KEY_BODY);
        int hash = 17;
        hash = 31 * hash + (int) (when ^ (when >>> 32));
        hash = 31 * hash + (title == null ? 0 : title.hashCode());
        hash = 31 * hash + (body == null ? 0 : body.hashCode());
        if (hash == Integer.MIN_VALUE) return 1;
        return hash == 0 ? 1 : Math.abs(hash);
    }
}
