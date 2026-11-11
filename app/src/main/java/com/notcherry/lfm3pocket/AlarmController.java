package com.notcherry.lfm3pocket;

import java.util.Locale;

/** Validates a time and reports only whether Android's clock app was opened for the alarm request. */
public final class AlarmController {
    public interface Launcher { void open(int hour, int minute, String label); }

    public static final class NoClockAppException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private final Launcher launcher;

    public AlarmController(Launcher launcher) {
        if (launcher == null) throw new IllegalArgumentException("Missing alarm launcher");
        this.launcher = launcher;
    }

    public AndroidActionExecutor.ActionResult request(int hour, int minute, String label) {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59 || !validLabel(label)) {
            return result(false, "INVALID_ARGUMENTS", "Alarm time must be 00:00..23:59 and label must be one line.");
        }
        try {
            launcher.open(hour, minute, label);
            return result(true, "ALARM_REQUEST_OPENED", "Alarm request sent to the Android clock for "
                    + String.format(Locale.ROOT, "%02d:%02d", hour, minute)
                    + ". Check Clock to verify it was set; this app cannot verify alarm creation.");
        } catch (NoClockAppException e) {
            return result(false, "CLOCK_APP_UNAVAILABLE", "No clock app can handle this alarm request.");
        } catch (SecurityException e) {
            return result(false, "ALARM_REQUEST_DENIED", "Android denied opening the clock app for this alarm request.");
        } catch (RuntimeException e) {
            return result(false, "ALARM_REQUEST_FAILED", "Android could not open the alarm request.");
        }
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
