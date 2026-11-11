package com.notcherry.lfm3pocket;

import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public final class AlarmControllerTest {
    @Test public void sendsExactAlarmTimeAndLabelWithoutClaimingTheClockConfirmedIt() {
        FakeLauncher launcher = new FakeLauncher();

        AndroidActionExecutor.ActionResult result = new AlarmController(launcher).request(7, 35, "Work");

        assertTrue(result.succeeded);
        assertEquals("ALARM_REQUEST_OPENED", result.status);
        assertEquals(7, launcher.hour);
        assertEquals(35, launcher.minute);
        assertEquals("Work", launcher.label);
        assertTrue(result.message.contains("07:35"));
        assertTrue(result.message.contains("verify"));
        assertFalse(result.message.toLowerCase().contains("alarm created"));
    }

    @Test public void acceptsAndroidHourAndMinuteBoundaries() {
        FakeLauncher launcher = new FakeLauncher();
        AlarmController controller = new AlarmController(launcher);

        assertTrue(controller.request(0, 0, "").succeeded);
        assertTrue(controller.request(23, 59, "Late").succeeded);
        assertEquals(23, launcher.hour);
        assertEquals(59, launcher.minute);
    }

    @Test public void rejectsInvalidTimeAndLabelWithoutOpeningClock() {
        FakeLauncher launcher = new FakeLauncher();
        AlarmController controller = new AlarmController(launcher);

        assertEquals("INVALID_ARGUMENTS", controller.request(-1, 0, "Work").status);
        assertEquals("INVALID_ARGUMENTS", controller.request(24, 0, "Work").status);
        assertEquals("INVALID_ARGUMENTS", controller.request(7, -1, "Work").status);
        assertEquals("INVALID_ARGUMENTS", controller.request(7, 60, "Work").status);
        assertEquals("INVALID_ARGUMENTS", controller.request(7, 30, "bad\nlabel").status);
        assertEquals(0, launcher.calls);
    }

    @Test public void missingClockAndPolicyDenialAreNotReportedAsSuccess() {
        FakeLauncher missing = new FakeLauncher();
        missing.failure = new AlarmController.NoClockAppException();
        assertEquals("CLOCK_APP_UNAVAILABLE", new AlarmController(missing).request(7, 30, "Work").status);

        FakeLauncher denied = new FakeLauncher();
        denied.failure = new SecurityException("denied");
        AndroidActionExecutor.ActionResult result = new AlarmController(denied).request(7, 30, "Work");
        assertFalse(result.succeeded);
        assertEquals("ALARM_REQUEST_DENIED", result.status);
    }

    @Test public void actionExecutorAndAndroidDispatcherRequireTheExactAlarmArguments() {
        FakeLauncher launcher = new FakeLauncher();
        AlarmActionExecutor alarms = new AlarmActionExecutor(new AlarmController(launcher));
        AndroidActionExecutor unused = (name, arguments) ->
                new AndroidActionExecutor.ActionResult(true, "WRONG_HANDLER", name);
        AndroidToolsExecutor dispatcher = new AndroidToolsExecutor(unused, unused, unused,
                new AlarmActionExecutor(new AlarmController(launcher)), unused);
        Map<String, String> args = new LinkedHashMap<>();
        args.put("hour", "8"); args.put("minute", "15"); args.put("label", "Standup");

        assertEquals("UNKNOWN_TOOL", alarms.execute("set_timer", args).status);
        assertEquals("INVALID_ARGUMENTS", alarms.execute("set_alarm", java.util.Collections.emptyMap()).status);
        Map<String, String> extra = new LinkedHashMap<>(args); extra.put("days", "[1,2,3]");
        assertEquals("INVALID_ARGUMENTS", alarms.execute("set_alarm", extra).status);
        assertEquals("ALARM_REQUEST_OPENED", dispatcher.execute("set_alarm", args).status);
        assertEquals("UNKNOWN_TOOL", dispatcher.execute("dismiss_alarm", args).status);
        assertEquals(1, launcher.calls);
        assertEquals(8, launcher.hour);
        assertEquals(15, launcher.minute);
        assertEquals("Standup", launcher.label);
    }

    private static final class FakeLauncher implements AlarmController.Launcher {
        int calls, hour, minute;
        String label;
        RuntimeException failure;
        @Override public void open(int requestedHour, int requestedMinute, String requestedLabel) {
            calls++;
            if (failure != null) throw failure;
            hour = requestedHour; minute = requestedMinute; label = requestedLabel;
        }
    }
}
