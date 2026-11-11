package com.notcherry.lfm3pocket;

import org.junit.Test;
import static org.junit.Assert.*;

public final class TimerControllerTest {
    @Test public void opensAnExactTimerRequestWithoutClaimingItWasCreated() {
        FakeLauncher launcher = new FakeLauncher();

        AndroidActionExecutor.ActionResult result = new TimerController(launcher).request(90, "Tea");

        assertTrue(result.succeeded);
        assertEquals("TIMER_REQUEST_OPENED", result.status);
        assertEquals(90, launcher.seconds);
        assertEquals("Tea", launcher.label);
        assertTrue(result.message.contains("verify it started"));
        assertFalse(result.message.toLowerCase().contains("timer created"));
    }

    @Test public void acceptsAndroidAlarmClockDurationBounds() {
        FakeLauncher launcher = new FakeLauncher();
        TimerController controller = new TimerController(launcher);

        assertTrue(controller.request(1, "").succeeded);
        assertTrue(controller.request(86400, "day").succeeded);
        assertEquals(86400, launcher.seconds);
    }

    @Test public void rejectsInvalidDurationOrLabelWithoutOpeningClock() {
        FakeLauncher launcher = new FakeLauncher();
        TimerController controller = new TimerController(launcher);

        assertEquals("INVALID_ARGUMENTS", controller.request(0, "Tea").status);
        assertEquals("INVALID_ARGUMENTS", controller.request(86401, "Tea").status);
        assertEquals("INVALID_ARGUMENTS", controller.request(30, "bad\nlabel").status);
        assertEquals("INVALID_ARGUMENTS", controller.request(30, new String(new char[101]).replace('\0', 'x')).status);
        assertEquals(0, launcher.calls);
    }

    @Test public void clockHandlerMissingAndPolicyDenialAreReportedAccurately() {
        FakeLauncher unavailable = new FakeLauncher();
        unavailable.failure = new TimerController.NoClockAppException();
        AndroidActionExecutor.ActionResult missing = new TimerController(unavailable).request(30, "Tea");
        assertFalse(missing.succeeded);
        assertEquals("CLOCK_APP_UNAVAILABLE", missing.status);

        FakeLauncher denied = new FakeLauncher();
        denied.failure = new SecurityException("denied");
        AndroidActionExecutor.ActionResult policy = new TimerController(denied).request(30, "Tea");
        assertFalse(policy.succeeded);
        assertEquals("TIMER_REQUEST_DENIED", policy.status);
    }

    @Test public void actionExecutorRequiresExactValidatedArguments() {
        FakeLauncher launcher = new FakeLauncher();
        TimerActionExecutor executor = new TimerActionExecutor(new TimerController(launcher));

        assertEquals("UNKNOWN_TOOL", executor.execute("set_alarm", java.util.Collections.emptyMap()).status);
        assertEquals("INVALID_ARGUMENTS", executor.execute("set_timer", java.util.Collections.emptyMap()).status);
        java.util.Map<String, String> args = new java.util.LinkedHashMap<>();
        args.put("seconds", "30.5"); args.put("label", "Tea");
        assertEquals("INVALID_ARGUMENTS", executor.execute("set_timer", args).status);
        args.put("seconds", "30"); args.put("unexpected", "true");
        assertEquals("INVALID_ARGUMENTS", executor.execute("set_timer", args).status);
        assertEquals(0, launcher.calls);
    }

    @Test public void androidToolsExecutorDispatchesOnlyTheTimerActionToItsAdapter() {
        FakeLauncher launcher = new FakeLauncher();
        AndroidActionExecutor unused = (name, arguments) ->
                new AndroidActionExecutor.ActionResult(true, "WRONG_HANDLER", name);
        AndroidToolsExecutor executor = new AndroidToolsExecutor(unused, unused,
                new TimerActionExecutor(new TimerController(launcher)), unused, unused, unused, unused);
        java.util.Map<String, String> args = new java.util.LinkedHashMap<>();
        args.put("seconds", "45");
        args.put("label", "Tea");

        AndroidActionExecutor.ActionResult dispatched = executor.execute("set_timer", args);
        AndroidActionExecutor.ActionResult unknown = executor.execute("open_thing", args);

        assertEquals("TIMER_REQUEST_OPENED", dispatched.status);
        assertEquals(45, launcher.seconds);
        assertEquals("Tea", launcher.label);
        assertEquals("UNKNOWN_TOOL", unknown.status);
        assertEquals(1, launcher.calls);
    }

    private static final class FakeLauncher implements TimerController.Launcher {
        int calls, seconds;
        String label;
        RuntimeException failure;
        @Override public void open(int requestedSeconds, String requestedLabel) {
            calls++;
            if (failure != null) throw failure;
            seconds = requestedSeconds;
            label = requestedLabel;
        }
    }
}
