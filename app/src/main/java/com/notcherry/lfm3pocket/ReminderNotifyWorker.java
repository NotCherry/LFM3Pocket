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
    static final String KEY_ID = "reminder_id";
    static final String KEY_NOTIFICATION_ID = "notification_id";
    static final String KEY_TITLE = "title";
    static final String KEY_BODY = "body";

    public ReminderNotifyWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public Result doWork() {
        String id = getInputData().getString(KEY_ID);
        String title = getInputData().getString(KEY_TITLE);
        String body = getInputData().getString(KEY_BODY);
        int notificationId = getInputData().getInt(KEY_NOTIFICATION_ID, 0);
        if (notificationId == 0) notificationId = ReminderIds.notificationId(id == null ? "reminder" : id);
        ReminderNotifier.show(getApplicationContext(), notificationId, title, body);
        if (id != null) new ReminderStore(getApplicationContext()).remove(id);
        return Result.success();
    }
}