package com.notcherry.lfm3pocket;

import android.content.Context;

import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Schedules an inexact local reminder notification via WorkManager. */
final class WorkManagerReminderScheduler implements ReminderScheduler {
    private final Context context;
    private final java.util.function.LongSupplier nowEpochSec;

    WorkManagerReminderScheduler(Context context) {
        this(context, () -> System.currentTimeMillis() / 1000L);
    }

    WorkManagerReminderScheduler(Context context, java.util.function.LongSupplier nowEpochSec) {
        Context applicationContext = null;
        try {
            if (context != null) applicationContext = context.getApplicationContext();
        } catch (RuntimeException ignored) {
            // A contract test may construct an unattached Application.
        }
        this.context = applicationContext == null ? context : applicationContext;
        this.nowEpochSec = nowEpochSec == null ? () -> System.currentTimeMillis() / 1000L : nowEpochSec;
    }

    @Override public AndroidActionExecutor.ActionResult schedule(long whenEpochSec, String title, String body) {
        if (context == null) {
            return new AndroidActionExecutor.ActionResult(false, "REMINDER_FAILED", "Android context is unavailable.");
        }
        long delaySec = whenEpochSec - nowEpochSec.getAsLong();
        if (delaySec <= 0) {
            return new AndroidActionExecutor.ActionResult(false, "INVALID_ARGUMENTS",
                    "Reminder time must be in the future.");
        }
        try {
            Data data = new Data.Builder()
                    .putLong(ReminderWorker.KEY_WHEN_EPOCH_SEC, whenEpochSec)
                    .putString(ReminderWorker.KEY_TITLE, title == null ? "" : title)
                    .putString(ReminderWorker.KEY_BODY, body == null ? "" : body)
                    .build();
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ReminderWorker.class)
                    .setInitialDelay(delaySec, TimeUnit.SECONDS)
                    .setInputData(data)
                    .build();
            // Inexact WorkManager scheduling by default; no exact alarm permission requested.
            WorkManager.getInstance(context).enqueue(request);
            return new AndroidActionExecutor.ActionResult(true, "REMINDER_SCHEDULED",
                    "Reminder scheduled: \"" + (title == null ? "" : title) + "\".");
        } catch (Exception e) {
            return new AndroidActionExecutor.ActionResult(false, "REMINDER_FAILED", "Could not schedule reminder.");
        }
    }
}
