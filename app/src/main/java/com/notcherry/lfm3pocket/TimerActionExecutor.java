package com.notcherry.lfm3pocket;

import java.util.Map;

/** Strict adapter for the validated set_timer arguments. */
public final class TimerActionExecutor implements AndroidActionExecutor {
    private final TimerController controller;

    public TimerActionExecutor(TimerController controller) {
        if (controller == null) throw new IllegalArgumentException("Missing timer controller");
        this.controller = controller;
    }

    @Override public ActionResult execute(String approvedToolName, Map<String, String> arguments) {
        if (!"set_timer".equals(approvedToolName)) {
            return new ActionResult(false, "UNKNOWN_TOOL", "This Android action is not enabled.");
        }
        if (arguments == null || arguments.size() != 2 || !arguments.containsKey("seconds")
                || !arguments.containsKey("label")) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "Expected exactly seconds and label arguments.");
        }
        String seconds = arguments.get("seconds");
        String label = arguments.get("label");
        if (seconds == null || !seconds.matches("[0-9]{1,5}") || label == null) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "Timer duration or label is invalid.");
        }
        try {
            return controller.request(Integer.parseInt(seconds), label);
        } catch (NumberFormatException e) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "Timer duration must be 1..86400 seconds.");
        }
    }
}
