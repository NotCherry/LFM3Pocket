package com.notcherry.lfm3pocket;

import java.util.Map;

/** Strict adapter for validated calendar tool arguments. */
public final class CalendarActionExecutor implements AndroidActionExecutor {
    private final CalendarGateway gateway;

    public CalendarActionExecutor(CalendarGateway gateway) {
        if (gateway == null) throw new IllegalArgumentException("Missing calendar gateway");
        this.gateway = gateway;
    }

    @Override
    public ActionResult execute(String approvedToolName, Map<String, String> arguments) {
        if (arguments == null) return invalid("Missing arguments.");
        try {
            if ("list_events".equals(approvedToolName)) {
                requireKeys(arguments, "window_start_epoch_sec", "window_end_epoch_sec", "limit");
                long start = parseLong(arguments.get("window_start_epoch_sec"), "window_start_epoch_sec");
                long end = parseLong(arguments.get("window_end_epoch_sec"), "window_end_epoch_sec");
                int limit = parseInt(arguments.get("limit"), "limit");
                return gateway.listEvents(start, end, limit);
            }
            if ("create_event".equals(approvedToolName)) {
                requireKeys(arguments, "title", "start_epoch_sec", "end_epoch_sec", "calendar_id", "all_day", "location", "notes");
                String title = requireString(arguments.get("title"), "title");
                long start = parseLong(arguments.get("start_epoch_sec"), "start_epoch_sec");
                long end = parseLong(arguments.get("end_epoch_sec"), "end_epoch_sec");
                String calendarId = arguments.get("calendar_id") == null ? "" : arguments.get("calendar_id");
                boolean allDay = parseBoolean(arguments.get("all_day"), "all_day");
                String location = arguments.get("location") == null ? "" : arguments.get("location");
                String notes = arguments.get("notes") == null ? "" : arguments.get("notes");
                return gateway.createEvent(title, start, end, calendarId, allDay, location, notes);
            }
            if ("update_event".equals(approvedToolName)) {
                requireKeys(arguments, "event_id", "title", "start_epoch_sec", "end_epoch_sec");
                String eventId = requireString(arguments.get("event_id"), "event_id");
                String title = requireString(arguments.get("title"), "title");
                long start = parseLong(arguments.get("start_epoch_sec"), "start_epoch_sec");
                long end = parseLong(arguments.get("end_epoch_sec"), "end_epoch_sec");
                return gateway.updateEvent(eventId, title, start, end);
            }
            if ("delete_event".equals(approvedToolName)) {
                requireKeys(arguments, "event_id");
                String eventId = requireString(arguments.get("event_id"), "event_id");
                return gateway.deleteEvent(eventId);
            }
        } catch (IllegalArgumentException e) {
            return invalid(e.getMessage() == null ? "Calendar arguments are invalid." : e.getMessage());
        } catch (RuntimeException e) {
            return new ActionResult(false, "CALENDAR_FAILED", "Calendar action failed.");
        }
        return new ActionResult(false, "UNKNOWN_TOOL", "This Android action is not enabled.");
    }

    private static void requireKeys(Map<String, String> arguments, String... keys) {
        if (arguments.size() != keys.length) throw new IllegalArgumentException("Unexpected calendar arguments.");
        for (String key : keys) {
            if (!arguments.containsKey(key)) throw new IllegalArgumentException("Missing argument: " + key);
        }
    }

    private static String requireString(String value, String key) {
        if (value == null) throw new IllegalArgumentException(key + " is required.");
        return value;
    }

    private static long parseLong(String value, String key) {
        if (value == null) throw new IllegalArgumentException(key + " is required.");
        try { return Long.parseLong(value.trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " must be an integer."); }
    }

    private static int parseInt(String value, String key) {
        if (value == null) throw new IllegalArgumentException(key + " is required.");
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " must be an integer."); }
    }

    private static boolean parseBoolean(String value, String key) {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new IllegalArgumentException(key + " must be boolean.");
    }

    private static ActionResult invalid(String message) {
        return new ActionResult(false, "INVALID_ARGUMENTS", message);
    }
}
