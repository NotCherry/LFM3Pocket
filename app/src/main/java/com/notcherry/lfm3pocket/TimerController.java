package com.notcherry.lfm3pocket;

/** Validates a requested duration and reports only whether Android's clock app was opened. */
public final class TimerController {
    public interface Launcher { void open(int seconds, String label); }

    public static final class NoClockAppException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private final Launcher launcher;

    public TimerController(Launcher launcher) {
        if (launcher == null) throw new IllegalArgumentException("Missing timer launcher");
        this.launcher = launcher;
    }

    public AndroidActionExecutor.ActionResult request(int seconds, String label) {
        if (seconds < 1 || seconds > 86400 || !validLabel(label)) {
            return result(false, "INVALID_ARGUMENTS", "Timer duration must be 1..86400 seconds and label must be one line.");
        }
        try {
            launcher.open(seconds, label);
            return result(true, "TIMER_REQUEST_OPENED", "Timer request sent to the Android clock for "
                    + seconds + " seconds. Check the clock app to verify it started; this app cannot verify timer creation.");
        } catch (NoClockAppException e) {
            return result(false, "CLOCK_APP_UNAVAILABLE", "No clock app can handle this timer request.");
        } catch (SecurityException e) {
            return result(false, "TIMER_REQUEST_DENIED", "Android denied opening the clock app for this timer request.");
        } catch (RuntimeException e) {
            return result(false, "TIMER_REQUEST_FAILED", "Android could not open the timer request.");
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
