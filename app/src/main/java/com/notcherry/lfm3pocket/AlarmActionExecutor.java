package com.notcherry.lfm3pocket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Strict adapter for the validated set_alarm / show_alarms arguments. */
public final class AlarmActionExecutor implements AndroidActionExecutor {
    private final AlarmController controller;

    public AlarmActionExecutor(AlarmController controller) {
        if (controller == null) throw new IllegalArgumentException("Missing alarm controller");
        this.controller = controller;
    }

    @Override public ActionResult execute(String approvedToolName, Map<String, String> arguments) {
        if ("show_alarms".equals(approvedToolName)) {
            if (arguments != null && !arguments.isEmpty()) {
                return new ActionResult(false, "INVALID_ARGUMENTS", "show_alarms takes no arguments.");
            }
            return controller.showAlarms();
        }
        if (!"set_alarm".equals(approvedToolName)) {
            return new ActionResult(false, "UNKNOWN_TOOL", "This Android action is not enabled.");
        }
        if (arguments == null || !arguments.containsKey("hour")
                || !arguments.containsKey("minute") || !arguments.containsKey("label")) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "Expected hour, minute, and label arguments.");
        }
        if (arguments.size() != 3 && !(arguments.size() == 4 && arguments.containsKey("days"))) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "Expected hour, minute, label, and optional days.");
        }
        String hour = arguments.get("hour");
        String minute = arguments.get("minute");
        String label = arguments.get("label");
        if (hour == null || !hour.matches("[0-9]{1,2}") || minute == null
                || !minute.matches("[0-9]{1,2}") || label == null) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "Alarm time or label is invalid.");
        }
        List<Integer> days;
        try {
            days = parseDays(arguments.get("days"));
        } catch (IllegalArgumentException e) {
            return new ActionResult(false, "INVALID_ARGUMENTS", e.getMessage());
        }
        try {
            return controller.request(Integer.parseInt(hour), Integer.parseInt(minute), label, days);
        } catch (NumberFormatException e) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "Alarm time must be 00:00..23:59.");
        }
    }

    static List<Integer> parseDays(String raw) {
        ArrayList<Integer> days = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty() || "[]".equals(raw.trim())) return days;
        String text = raw.trim();
        if (text.startsWith("[") && text.endsWith("]")) text = text.substring(1, text.length() - 1).trim();
        if (text.isEmpty()) return days;
        for (String part : text.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) throw new IllegalArgumentException("days entries must be Calendar weekdays 1..7");
            int day = Integer.parseInt(token);
            if (day < 1 || day > 7 || days.contains(day)) {
                throw new IllegalArgumentException("days entries must be unique Calendar weekdays 1..7");
            }
            days.add(day);
        }
        return days;
    }
}
