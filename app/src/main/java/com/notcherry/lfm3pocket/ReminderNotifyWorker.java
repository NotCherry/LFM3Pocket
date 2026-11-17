package com.notcherry.lfm3pocket;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Posts the reminder notification when WorkManager fires. */
public final class ReminderNotifyWorker extends Worker {
    static final String WORK_TAG = "hanami_reminder";
    static final String KEY_TITLE = "title";
    static final String KEY_BODY = "body";

    public ReminderNotifyWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public Result doWork() {
        String title = getInputData().getString(KEY_TITLE);
        String body = getInputData().getString(KEY_BODY);
        if (title == null) title = "";
        if (body == null) body = "";
        HanamiNotifications.ensureChannels(getApplicationContext());
        Intent open = new Intent(getApplicationContext(), MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(
                getApplicationContext(), 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(getApplicationContext(),
                HanamiNotifications.REMINDER_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_runtime)
                .setContentTitle(title.isEmpty()
                        ? getApplicationContext().getString(R.string.reminder_default_title) : title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(content);
        android.app.NotificationManager manager =
                getApplicationContext().getSystemService(android.app.NotificationManager.class);
        if (manager == null) return Result.failure();
        manager.notify((int) (System.currentTimeMillis() & 0x7FFFFFFF), builder.build());
        return Result.success();
    }
}