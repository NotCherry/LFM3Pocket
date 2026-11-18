package com.notcherry.lfm3pocket;

import java.util.Map;

/** Strict adapter for the validated set_reminder arguments. */
public final class ReminderActionExecutor implements AndroidActionExecutor {
    private final ReminderScheduler scheduler;

    public ReminderActionExecutor(ReminderScheduler scheduler) {
        if (scheduler == null) throw new IllegalArgumentException("Missing reminder scheduler");
        this.scheduler = scheduler;
    }

    @Override public ActionResult execute(String approvedToolName, Map<String, String> arguments) {
        if (!"set_reminder".equals(approvedToolName)) {
            return new ActionResult(false, "UNKNOWN_TOOL", "This Android action is not enabled.");
        }
        if (arguments == null || arguments.size() != 3
                || !arguments.containsKey("when_epoch_sec")
                || !arguments.containsKey("title")
                || !arguments.containsKey("body")) {
            return new ActionResult(false, "INVALID_ARGUMENTS",
                    "Expected exactly when_epoch_sec, title, and body arguments.");
        }
        String whenRaw = arguments.get("when_epoch_sec");
        String title = arguments.get("title");
        String body = arguments.get("body");
        if (whenRaw == null || title == null || body == null) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "Reminder arguments are invalid.");
        }
        long whenEpochSec;
        try {
            whenEpochSec = Long.parseLong(whenRaw);
        } catch (NumberFormatException e) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "when_epoch_sec must be an integer epoch second.");
        }
        return scheduler.schedule(whenEpochSec, title, body);
    }
}
