package com.notcherry.lfm3pocket;

import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Validates reminder times, prevents duplicates, and enqueues notifications. */
public final class ReminderController {
    static final long MIN_LEAD_SECONDS = 30;
    static final long MAX_HORIZON_SECONDS = 365L * 24 * 60 * 60;
    static final long MIN_EPOCH = 946_684_800L;
    static final long MAX_EPOCH = 4_102_444_800L;
    static final int MAX_TITLE = 100;
    static final int MAX_BODY = 500;

    private final ReminderScheduler scheduler;
    private final ReminderBook store;
    private final LongSupplier nowEpochSec;

    public ReminderController(ReminderScheduler scheduler, ReminderBook store, LongSupplier nowEpochSec) {
        if (scheduler == null) throw new IllegalArgumentException("Missing reminder scheduler");
        if (store == null) throw new IllegalArgumentException("Missing reminder store");
        if (nowEpochSec == null) throw new IllegalArgumentException("Missing clock");
        this.scheduler = scheduler;
        this.store = store;
        this.nowEpochSec = nowEpochSec;
    }

    public AndroidActionExecutor.ActionResult request(long whenEpochSec, String title, String body) {
        if (title == null || body == null) {
            return fail("INVALID_ARGUMENTS", "Reminder title and body are required.");
        }
        if (!validTitle(title)) {
            return fail("INVALID_ARGUMENTS", "Reminder title must be 1..100 characters on one line.");
        }
        if (!validBody(body)) {
            return fail("INVALID_ARGUMENTS", "Reminder body must be at most 500 characters on one line.");
        }
        if (whenEpochSec < MIN_EPOCH || whenEpochSec > MAX_EPOCH) {
            return fail("INVALID_ARGUMENTS", "Reminder time is out of supported bounds.");
        }
        long now = nowEpochSec.getAsLong();
        long lead = whenEpochSec - now;
        if (lead < MIN_LEAD_SECONDS) {
            return fail("INVALID_ARGUMENTS", "Reminder time must be at least 30 seconds from now.");
        }
        if (lead > MAX_HORIZON_SECONDS) {
            return fail("INVALID_ARGUMENTS", "Reminder time must be within one year.");
        }
        if (store.hasDuplicate(title, whenEpochSec)) {
            return fail("REMINDER_DUPLICATE", "A reminder with the same title and time is already scheduled.");
        }
        String workId = "reminder-" + UUID.randomUUID();
        int notificationId = ReminderIds.notificationId(workId);
        ReminderScheduler.Result scheduled = scheduler.schedule(
                new ReminderScheduler.Plan(workId, whenEpochSec, title, body, notificationId));
        if (!scheduled.succeeded) {
            return fail(scheduled.status, scheduled.message);
        }
        return new AndroidActionExecutor.ActionResult(true, scheduled.status, scheduled.message);
    }

    public AndroidActionExecutor.ActionResult cancel(String title) {
        if (!validTitle(title)) {
            return fail("INVALID_ARGUMENTS", "Reminder title must be 1..100 characters on one line.");
        }
        List<ReminderStore.Entry> matches = store.findByTitle(title);
        if (matches.isEmpty()) {
            return fail("REMINDER_NOT_FOUND", "No scheduled reminder matches that title.");
        }
        if (matches.size() > 1) {
            return fail("REMINDER_AMBIGUOUS",
                    "Multiple reminders share that title; cancel after the latest one was scheduled.");
        }
        ReminderStore.Entry entry = matches.get(0);
        scheduler.cancel(entry.id);
        return new AndroidActionExecutor.ActionResult(true, "REMINDER_CANCELLED",
                "Cancelled reminder \"" + entry.title + "\".");
    }

    private static boolean validTitle(String title) {
        if (title.isEmpty() || title.length() > MAX_TITLE) return false;
        return oneLine(title);
    }

    private static boolean validBody(String body) {
        if (body.length() > MAX_BODY) return false;
        return oneLine(body);
    }

    private static boolean oneLine(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) || c == '\u2028' || c == '\u2029') return false;
        }
        return true;
    }

    private static AndroidActionExecutor.ActionResult fail(String status, String message) {
        return new AndroidActionExecutor.ActionResult(false, status, message);
    }
}