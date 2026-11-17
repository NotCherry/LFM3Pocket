package com.notcherry.lfm3pocket;

import java.util.UUID;
import java.util.function.LongSupplier;

/** Validates reminder times and enqueues WorkManager-backed local notifications. */
public final class ReminderController {
    static final long MIN_LEAD_SECONDS = 30;
    static final long MAX_HORIZON_SECONDS = 365L * 24 * 60 * 60;
    static final long MIN_EPOCH = 946_684_800L;
    static final long MAX_EPOCH = 4_102_444_800L;

    private final ReminderScheduler scheduler;
    private final LongSupplier nowEpochSec;

    public ReminderController(ReminderScheduler scheduler, LongSupplier nowEpochSec) {
        if (scheduler == null) throw new IllegalArgumentException("Missing reminder scheduler");
        if (nowEpochSec == null) throw new IllegalArgumentException("Missing clock");
        this.scheduler = scheduler;
        this.nowEpochSec = nowEpochSec;
    }

    public AndroidActionExecutor.ActionResult request(long whenEpochSec, String title, String body) {
        if (title == null || body == null) {
            return fail("INVALID_ARGUMENTS", "Reminder title and body are required.");
        }
        if (!validTitle(title)) {
            return fail("INVALID_ARGUMENTS", "Reminder title must be 1..200 characters on one line.");
        }
        if (!validBody(body)) {
            return fail("INVALID_ARGUMENTS", "Reminder body must be at most 2000 characters on one line.");
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
        String workId = "reminder-" + UUID.randomUUID();
        ReminderScheduler.Result scheduled = scheduler.schedule(
                new ReminderScheduler.Plan(workId, whenEpochSec, title, body));
        if (!scheduled.succeeded) {
            return fail(scheduled.status, scheduled.message);
        }
        return new AndroidActionExecutor.ActionResult(true, scheduled.status, scheduled.message);
    }

    private static boolean validTitle(String title) {
        if (title.isEmpty() || title.length() > 200) return false;
        return oneLine(title);
    }

    private static boolean validBody(String body) {
        if (body.length() > 2000) return false;
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