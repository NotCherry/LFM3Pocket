package com.notcherry.lfm3pocket;

import android.content.Context;

import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Inexact one-shot reminders via WorkManager (survives process death; no exact-alarm permission). */
public final class WorkManagerReminderScheduler implements ReminderScheduler {
    private final Context context;

    public WorkManagerReminderScheduler(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override public Result schedule(Plan plan) {
        if (plan == null || plan.workId == null || plan.workId.isEmpty()) {
            return new Result(false, "INVALID_ARGUMENTS", "Missing reminder work id.");
        }
        long delayMs = Math.max(0L, plan.whenEpochSec * 1000L - System.currentTimeMillis());
        Data input = new Data.Builder()
                .putString(ReminderNotifyWorker.KEY_ID, plan.workId)
                .putInt(ReminderNotifyWorker.KEY_NOTIFICATION_ID, plan.notificationId)
                .putString(ReminderNotifyWorker.KEY_TITLE, plan.title == null ? "" : plan.title)
                .putString(ReminderNotifyWorker.KEY_BODY, plan.body == null ? "" : plan.body)
                .build();
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(ReminderNotifyWorker.class)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(input)
                .addTag(ReminderNotifyWorker.WORK_TAG)
                .build();
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                    plan.workId, ExistingWorkPolicy.REPLACE, work);
        } catch (RuntimeException e) {
            return new Result(false, "REMINDER_SCHEDULE_FAILED",
                    "WorkManager could not schedule the reminder.");
        }
        return new Result(true, "REMINDER_SCHEDULED",
                "Reminder scheduled (inexact) for \"" + plan.title + "\".");
    }

    @Override public void cancel(String workId) {
        if (workId == null || workId.isEmpty()) return;
        try {
            WorkManager.getInstance(context).cancelUniqueWork(workId);
        } catch (RuntimeException ignored) {
        }
    }
}