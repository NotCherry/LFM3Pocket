package com.notcherry.lfm3pocket;

import java.util.Map;

/** Strict adapter for the validated set_reminder arguments. */
public final class ReminderActionExecutor implements AndroidActionExecutor {
    private final ReminderController controller;

    public ReminderActionExecutor(ReminderController controller) {
        if (controller == null) throw new IllegalArgumentException("Missing reminder controller");
        this.controller = controller;
    }

    @Override public ActionResult execute(String approvedToolName, Map<String, String> arguments) {
        if (!"set_reminder".equals(approvedToolName)) {
            return new ActionResult(false, "UNKNOWN_TOOL", "This Android action is not enabled.");
        }
        if (arguments == null || arguments.size() != 3 || !arguments.containsKey("when_epoch_sec")
                || !arguments.containsKey("title") || !arguments.containsKey("body")) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "Expected exactly when_epoch_sec, title, and body.");
        }
        String when = arguments.get("when_epoch_sec");
        String title = arguments.get("title");
        String body = arguments.get("body");
        if (when == null || !when.matches("[0-9]{10}")) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "when_epoch_sec must be a 10-digit Unix epoch second.");
        }
        try {
            return controller.request(Long.parseLong(when), title, body);
        } catch (NumberFormatException e) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "when_epoch_sec is invalid.");
        }
    }
}