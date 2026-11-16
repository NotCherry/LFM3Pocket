package com.notcherry.lfm3pocket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Validates a time and reports only whether Android's clock app was opened for the alarm request. */
public final class AlarmController {
    public interface Launcher {
        void open(int hour, int minute, String label, List<Integer> days);
        void showAlarms();
    }

    public static final class NoClockAppException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private final Launcher launcher;

    public AlarmController(Launcher launcher) {
        if (launcher == null) throw new IllegalArgumentException("Missing alarm launcher");
        this.launcher = launcher;
    }

    public AndroidActionExecutor.ActionResult request(int hour, int minute, String label) {
        return request(hour, minute, label, Collections.emptyList());
    }

    public AndroidActionExecutor.ActionResult request(int hour, int minute, String label, List<Integer> days) {
        List<Integer> weekdays = normalizeDays(days);
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59 || !validLabel(label) || weekdays == null) {
            return result(false, "INVALID_ARGUMENTS",
                    "Alarm time must be 00:00..23:59, label must be one line, and days must be Calendar 1..7.");
        }
        try {
            launcher.open(hour, minute, label, weekdays);
            StringBuilder message = new StringBuilder("About to open the Android clock to set an alarm for ")
                    .append(String.format(Locale.ROOT, "%02d:%02d", hour, minute));
            if (!label.isEmpty()) message.append(" labelled \"").append(label).append('"');
            if (!weekdays.isEmpty()) message.append(" on days ").append(weekdays);
            message.append(". Check Clock to verify it was set; this app cannot verify alarm creation.");
            return result(true, "ALARM_REQUEST_OPENED", message.toString());
        } catch (NoClockAppException e) {
            return result(false, "CLOCK_APP_UNAVAILABLE", "No clock app can handle this alarm request.");
        } catch (SecurityException e) {
            return result(false, "ALARM_REQUEST_DENIED", "Android denied opening the clock app for this alarm request.");
        } catch (RuntimeException e) {
            return result(false, "ALARM_REQUEST_FAILED", "Android could not open the alarm request.");
        }
    }

    public AndroidActionExecutor.ActionResult showAlarms() {
        try {
            launcher.showAlarms();
            return result(true, "SHOW_ALARMS_OPENED",
                    "Opened the Android clock alarm list. Confirm what you see there; this app cannot read alarms.");
        } catch (NoClockAppException e) {
            return result(false, "CLOCK_APP_UNAVAILABLE", "No clock app can show alarms.");
        } catch (SecurityException e) {
            return result(false, "ALARM_REQUEST_DENIED", "Android denied opening the clock app alarm list.");
        } catch (RuntimeException e) {
            return result(false, "ALARM_REQUEST_FAILED", "Android could not open the alarm list.");
        }
    }

    private static List<Integer> normalizeDays(List<Integer> days) {
        if (days == null) return Collections.emptyList();
        ArrayList<Integer> copy = new ArrayList<>();
        for (Integer day : days) {
            if (day == null || day < 1 || day > 7 || copy.contains(day)) return null;
            copy.add(day);
        }
        return copy;
    }

    private static boolean validLabel(String label) {
        if (label == null || label.length() > 100) return false;
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (Character.isISOControl(c) || c == '\u2028' || c == '\u2029') return false;
        }
        return true;
    }

    private static AndroidActionExecutor.ActionResult result(boolean success, String status, String message) {
        return new AndroidActionExecutor.ActionResult(success, status, message);
    }
}
