package com.notcherry.lfm3pocket;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Exact {@link AlarmManager} when allowed; otherwise inexact WorkManager with user-visible limits.
 * Entries are persisted in {@link ReminderStore} and re-queued on boot / app start.
 */
public final class AndroidReminderScheduler implements ReminderScheduler {
  static final String ACTION_FIRE = "com.notcherry.lfm3pocket.action.REMINDER_FIRE";
  static final String EXTRA_ID = "reminder_id";

  private final Context context;
  private final ReminderStore store;
  private final WorkManagerReminderScheduler workFallback;

  public AndroidReminderScheduler(Context context, ReminderStore store) {
    this.context = context.getApplicationContext();
    this.store = store;
    this.workFallback = new WorkManagerReminderScheduler(this.context);
  }

  public static void rescheduleAll(Context context) {
    ReminderStore store = new ReminderStore(context);
    AndroidReminderScheduler scheduler = new AndroidReminderScheduler(context, store);
    long now = System.currentTimeMillis() / 1000L;
    for (ReminderStore.Entry entry : store.all()) {
      if (entry.whenEpochSec <= now) {
        store.remove(entry.id);
        continue;
      }
      scheduler.enqueue(entry, true);
    }
  }

  @Override public Result schedule(Plan plan) {
    if (plan == null || plan.workId == null || plan.workId.isEmpty()) {
      return new Result(false, "INVALID_ARGUMENTS", "Missing reminder id.");
    }
    boolean exact = canScheduleExactAlarms();
    ReminderStore.Entry entry = new ReminderStore.Entry(
            plan.workId, plan.whenEpochSec, plan.title, plan.body, plan.notificationId, exact);
    store.put(entry);
    return enqueue(entry, false);
  }

  private Result enqueue(ReminderStore.Entry entry, boolean silentReschedule) {
    if (canScheduleExactAlarms()) {
      cancelWork(entry.id);
      if (scheduleExact(entry)) {
        if (silentReschedule) return new Result(true, "REMINDER_SCHEDULED", "Rescheduled.");
        return new Result(true, "REMINDER_SCHEDULED", scheduleMessage(entry, true));
      }
    }
    cancelAlarm(entry.id, entry.notificationId);
    Result fallback = scheduleWork(entry);
    if (!fallback.succeeded) return fallback;
    if (silentReschedule) return fallback;
    return new Result(true, "REMINDER_SCHEDULED", scheduleMessage(entry, false));
  }

  @Override public void cancel(String workId) {
    if (workId == null || workId.isEmpty()) return;
    ReminderStore.Entry entry = store.findById(workId);
    int notificationId = entry == null ? 0 : entry.notificationId;
    cancelAlarm(workId, notificationId);
    cancelWork(workId);
    store.remove(workId);
  }

  private boolean canScheduleExactAlarms() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
    AlarmManager manager = context.getSystemService(AlarmManager.class);
    return manager != null && manager.canScheduleExactAlarms();
  }

  private boolean scheduleExact(ReminderStore.Entry entry) {
    AlarmManager manager = context.getSystemService(AlarmManager.class);
    if (manager == null) return false;
    Intent intent = alarmIntent(context, entry.id, entry.title, entry.body, entry.notificationId);
    PendingIntent pending = PendingIntent.getBroadcast(
            context, entry.notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    long triggerAtMillis = entry.whenEpochSec * 1000L;
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending);
      } else {
        manager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending);
      }
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private Result scheduleWork(ReminderStore.Entry entry) {
    long delayMs = Math.max(0L, entry.whenEpochSec * 1000L - System.currentTimeMillis());
    Data input = new Data.Builder()
            .putString(ReminderNotifyWorker.KEY_ID, entry.id)
            .putInt(ReminderNotifyWorker.KEY_NOTIFICATION_ID, entry.notificationId)
            .putString(ReminderNotifyWorker.KEY_TITLE, entry.title)
            .putString(ReminderNotifyWorker.KEY_BODY, entry.body)
            .build();
    OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(ReminderNotifyWorker.class)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(input)
            .addTag(ReminderNotifyWorker.WORK_TAG)
            .build();
    try {
      WorkManager.getInstance(context).enqueueUniqueWork(
              entry.id, ExistingWorkPolicy.REPLACE, work);
    } catch (RuntimeException e) {
      store.remove(entry.id);
      return new Result(false, "REMINDER_SCHEDULE_FAILED", "WorkManager could not schedule the reminder.");
    }
    return new Result(true, "REMINDER_INEXACT",
            "Reminder queued with inexact delivery (may be delayed under Doze).");
  }

  private void cancelAlarm(String workId, int notificationId) {
    try {
      PendingIntent pending = PendingIntent.getBroadcast(
              context, notificationId,
              alarmIntent(context, workId, "", "", notificationId),
              PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
      if (pending != null) {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        if (manager != null) manager.cancel(pending);
        pending.cancel();
      }
    } catch (RuntimeException ignored) {
    }
  }

  private void cancelWork(String workId) {
    workFallback.cancel(workId);
  }

  static Intent alarmIntent(Context context, String id, String title, String body, int notificationId) {
    Intent intent = new Intent(context, ReminderAlarmReceiver.class);
    intent.setAction(ACTION_FIRE);
    intent.putExtra(EXTRA_ID, id);
    intent.putExtra(ReminderNotifyWorker.KEY_TITLE, title);
    intent.putExtra(ReminderNotifyWorker.KEY_BODY, body);
    intent.putExtra(ReminderNotifyWorker.KEY_NOTIFICATION_ID, notificationId);
    return intent;
  }

  private static String scheduleMessage(ReminderStore.Entry entry, boolean exact) {
    String when = ReminderDescriptions.formatLocal(entry.whenEpochSec);
    String base = "Reminder \"" + entry.title + "\" at " + when;
    if (exact) {
      return base + ". Exact timing is enabled.";
    }
    return base + ". Inexact delivery: may be delayed under Doze; force-stop clears reminders until Hanami is opened again.";
  }
}