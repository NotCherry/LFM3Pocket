package com.notcherry.lfm3pocket;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
        assertTrue(result.message.toLowerCase().contains("about to open"));
        assertTrue(result.message.contains("verify"));
        assertFalse(result.message.toLowerCase().contains("alarm created"));
    }

    @Test public void acceptsAndroidHourAndMinuteBoundariesAndOptionalDays() {
        FakeLauncher launcher = new FakeLauncher();
        AlarmController controller = new AlarmController(launcher);

        assertTrue(controller.request(0, 0, "").succeeded);
        assertTrue(controller.request(23, 59, "Late", Arrays.asList(2, 3, 4, 5, 6)).succeeded);
        assertEquals(23, launcher.hour);
        assertEquals(59, launcher.minute);
        assertEquals(Arrays.asList(2, 3, 4, 5, 6), launcher.days);
    }

    @Test public void rejectsInvalidTimeLabelAndDaysWithoutOpeningClock() {
        FakeLauncher launcher = new FakeLauncher();
        AlarmController controller = new AlarmController(launcher);

        assertEquals("INVALID_ARGUMENTS", controller.request(-1, 0, "Work").status);
        assertEquals("INVALID_ARGUMENTS", controller.request(24, 0, "Work").status);
        assertEquals("INVALID_ARGUMENTS", controller.request(7, -1, "Work").status);
        assertEquals("INVALID_ARGUMENTS", controller.request(7, 60, "Work").status);
        assertEquals("INVALID_ARGUMENTS", controller.request(7, 30, "bad\nlabel").status);
        assertEquals("INVALID_ARGUMENTS", controller.request(7, 30, "Work", Arrays.asList(0)).status);
        assertEquals("INVALID_ARGUMENTS", controller.request(7, 30, "Work", Arrays.asList(1, 1)).status);
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

    @Test public void showAlarmsOpensTheClockListAndReportsMissingApps() {
        FakeLauncher launcher = new FakeLauncher();
        assertEquals("SHOW_ALARMS_OPENED", new AlarmController(launcher).showAlarms().status);
        assertEquals(1, launcher.showCalls);

        FakeLauncher missing = new FakeLauncher();
        missing.failure = new AlarmController.NoClockAppException();
        assertEquals("CLOCK_APP_UNAVAILABLE", new AlarmController(missing).showAlarms().status);
    }

    @Test public void actionExecutorAndAndroidDispatcherRequireTheExactAlarmArguments() {
        FakeLauncher launcher = new FakeLauncher();
        AlarmActionExecutor alarms = new AlarmActionExecutor(new AlarmController(launcher));
        AndroidActionExecutor unused = (name, arguments) ->
                new AndroidActionExecutor.ActionResult(true, "WRONG_HANDLER", name);
        AndroidToolsExecutor dispatcher = new AndroidToolsExecutor(unused, unused, unused,
                new AlarmActionExecutor(new AlarmController(launcher)), unused, unused, unused);
        Map<String, String> args = new LinkedHashMap<>();
        args.put("hour", "8"); args.put("minute", "15"); args.put("label", "Standup");

        assertEquals("UNKNOWN_TOOL", alarms.execute("set_timer", args).status);
        assertEquals("INVALID_ARGUMENTS", alarms.execute("set_alarm", java.util.Collections.emptyMap()).status);
        Map<String, String> withDays = new LinkedHashMap<>(args); withDays.put("days", "2,3,4,5,6");
        assertEquals("ALARM_REQUEST_OPENED", alarms.execute("set_alarm", withDays).status);
        assertEquals(Arrays.asList(2, 3, 4, 5, 6), launcher.days);
        assertEquals("SHOW_ALARMS_OPENED", dispatcher.execute("show_alarms", Collections.emptyMap()).status);
        assertEquals("UNKNOWN_TOOL", dispatcher.execute("dismiss_alarm", args).status);
        assertEquals(1, launcher.calls);
        assertEquals(1, launcher.showCalls);
    }

    private static final class FakeLauncher implements AlarmController.Launcher {
        int calls, showCalls, hour, minute;
        String label;
        List<Integer> days = Collections.emptyList();
        RuntimeException failure;
        @Override public void open(int requestedHour, int requestedMinute, String requestedLabel, List<Integer> requestedDays) {
            calls++;
            if (failure != null) throw failure;
            hour = requestedHour; minute = requestedMinute; label = requestedLabel;
            days = requestedDays == null ? Collections.emptyList() : requestedDays;
        }
        @Override public void showAlarms() {
            showCalls++;
            if (failure != null) throw failure;
        }
    }
}
