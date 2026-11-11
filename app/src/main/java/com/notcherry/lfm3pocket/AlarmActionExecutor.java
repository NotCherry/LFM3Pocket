package com.notcherry.lfm3pocket;

import java.util.Map;

/** Strict adapter for the validated set_alarm arguments. */
public final class AlarmActionExecutor implements AndroidActionExecutor {
    private final AlarmController controller;

    public AlarmActionExecutor(AlarmController controller) {
        if (controller == null) throw new IllegalArgumentException("Missing alarm controller");
        this.controller = controller;
    }

    @Override public ActionResult execute(String approvedToolName, Map<String, String> arguments) {
        if (!"set_alarm".equals(approvedToolName)) {
            return new ActionResult(false, "UNKNOWN_TOOL", "This Android action is not enabled.");
        }
        if (arguments == null || arguments.size() != 3 || !arguments.containsKey("hour")
                || !arguments.containsKey("minute") || !arguments.containsKey("label")) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "Expected exactly hour, minute, and label arguments.");
        }
        String hour = arguments.get("hour");
        String minute = arguments.get("minute");
        String label = arguments.get("label");
        if (hour == null || !hour.matches("[0-9]{1,2}") || minute == null
                || !minute.matches("[0-9]{1,2}") || label == null) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "Alarm time or label is invalid.");
        }
        try {
            return controller.request(Integer.parseInt(hour), Integer.parseInt(minute), label);
        } catch (NumberFormatException e) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "Alarm time must be 00:00..23:59.");
        }
    }
}
