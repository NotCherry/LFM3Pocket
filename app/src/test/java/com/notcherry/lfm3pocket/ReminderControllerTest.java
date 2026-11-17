package com.notcherry.lfm3pocket;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ReminderControllerTest {
    private static final long NOW = 1_700_000_000L;
    private static final long VALID_WHEN = NOW + 600L;

    @Test public void schedulesFutureReminderWithExactTitleAndBody() {
        FakeScheduler scheduler = new FakeScheduler();
        ReminderController controller = new ReminderController(scheduler, () -> NOW);
        AndroidActionExecutor.ActionResult result = controller.request(VALID_WHEN, "Buy milk", "2%");

        assertTrue(result.succeeded);
        assertEquals("REMINDER_SCHEDULED", result.status);
        assertEquals(1, scheduler.plans.size());
        assertEquals(VALID_WHEN, scheduler.plans.get(0).whenEpochSec);
        assertEquals("Buy milk", scheduler.plans.get(0).title);
        assertEquals("2%", scheduler.plans.get(0).body);
        assertTrue(result.message.contains("Buy milk"));
    }

    @Test public void rejectsPastTooSoonTooFarAndMalformedTextWithoutScheduling() {
        FakeScheduler scheduler = new FakeScheduler();
        ReminderController controller = new ReminderController(scheduler, () -> NOW);

        assertEquals("INVALID_ARGUMENTS", controller.request(NOW + 10, "A", "B").status);
        assertEquals("INVALID_ARGUMENTS", controller.request(NOW + ReminderController.MAX_HORIZON_SECONDS + 1, "A", "B").status);
        assertEquals("INVALID_ARGUMENTS", controller.request(VALID_WHEN, "", "B").status);
        assertEquals("INVALID_ARGUMENTS", controller.request(VALID_WHEN, "bad\nline", "B").status);
        assertEquals("INVALID_ARGUMENTS", controller.request(VALID_WHEN, "OK", "bad\nline").status);
        assertEquals(0, scheduler.plans.size());
    }

    @Test public void propagatesSchedulerFailure() {
        FakeScheduler scheduler = new FakeScheduler();
        scheduler.fail = true;
        ReminderController controller = new ReminderController(scheduler, () -> NOW);
        AndroidActionExecutor.ActionResult result = controller.request(VALID_WHEN, "Tea", "5 min");
        assertFalse(result.succeeded);
        assertEquals("REMINDER_SCHEDULE_FAILED", result.status);
    }

    @Test public void actionExecutorRequiresExactValidatedArguments() {
        FakeScheduler scheduler = new FakeScheduler();
        ReminderActionExecutor executor = new ReminderActionExecutor(
                new ReminderController(scheduler, () -> NOW));
        assertEquals("UNKNOWN_TOOL", executor.execute("set_timer", Map.of()).status);
        assertEquals("INVALID_ARGUMENTS", executor.execute("set_reminder", Map.of()).status);
        Map<String, String> args = new LinkedHashMap<>();
        args.put("when_epoch_sec", String.valueOf(VALID_WHEN));
        args.put("title", "Tea");
        args.put("body", "");
        args.put("unexpected", "x");
        assertEquals("INVALID_ARGUMENTS", executor.execute("set_reminder", args).status);
        args.remove("unexpected");
        assertTrue(executor.execute("set_reminder", args).succeeded);
        assertEquals(1, scheduler.plans.size());
    }

    @Test public void androidToolsExecutorDispatchesOnlySetReminder() {
        FakeScheduler scheduler = new FakeScheduler();
        AndroidActionExecutor reminder = new ReminderActionExecutor(new ReminderController(scheduler, () -> NOW));
        AndroidActionExecutor unused = (name, arguments) ->
                new AndroidActionExecutor.ActionResult(true, "WRONG_HANDLER", name);
        AndroidToolsExecutor dispatcher = new AndroidToolsExecutor(unused, unused, unused, unused, reminder,
                unused, unused, unused);
        Map<String, String> args = new LinkedHashMap<>();
        args.put("when_epoch_sec", String.valueOf(VALID_WHEN));
        args.put("title", "Stand");
        args.put("body", "Stretch");

        assertEquals("REMINDER_SCHEDULED", dispatcher.execute("set_reminder", args).status);
        assertEquals("UNKNOWN_TOOL", dispatcher.execute("set_timer", args).status);
        assertEquals(1, scheduler.plans.size());
    }

    @Test public void voiceConfirmApproveSchedulesAndRejectHasNoSideEffect() {
        FakeScheduler scheduler = new FakeScheduler();
        ToolCoordinator.Handler handler = request -> {
            if (!(request instanceof ToolRegistry.ReminderRequest)) {
                return new AndroidActionExecutor.ActionResult(false, "NOT_IMPLEMENTED", "");
            }
            ToolRegistry.ReminderRequest reminder = (ToolRegistry.ReminderRequest) request;
            return new ReminderActionExecutor(new ReminderController(scheduler, () -> NOW))
                    .execute("set_reminder", Map.of(
                            "when_epoch_sec", String.valueOf(reminder.whenEpochSec),
                            "title", reminder.title,
                            "body", reminder.body));
        };
        ToolCoordinator coordinator = new ToolCoordinator(handler, request -> true, () -> 0L);
        String json = "{\"tool\":\"set_reminder\",\"arguments\":{"
                + "\"when_epoch_sec\":" + VALID_WHEN + ",\"title\":\"Water\",\"body\":\"Drink\"}}";
        ToolRegistry.Request parsed = new ToolRegistry().parse(json).request;
        assertEquals(ToolCoordinator.Risk.CONFIRM, ToolCoordinator.risk(parsed));

        ToolCoordinator.Outcome pending = coordinator.propose(parsed, ToolCoordinator.Source.MODEL_PROPOSAL);
        assertEquals("PENDING_CONFIRMATION", pending.status);
        assertEquals(0, scheduler.plans.size());

        coordinator.reject(pending.proposal.id);
        assertEquals(0, scheduler.plans.size());

        ToolCoordinator.Outcome retry = coordinator.propose(parsed, ToolCoordinator.Source.MODEL_PROPOSAL);
        assertTrue(coordinator.approve(retry.proposal.id).result.succeeded);
        assertEquals(1, scheduler.plans.size());
        assertEquals("Water", scheduler.plans.get(0).title);
    }

    @Test public void registryValidatesReminderSchema() {
        ToolRegistry registry = new ToolRegistry();
        String valid = "{\"tool\":\"set_reminder\",\"arguments\":{"
                + "\"when_epoch_sec\":" + VALID_WHEN + ",\"title\":\"Call\",\"body\":\"Mom\"}}";
        assertTrue(registry.parse(valid).isValid());
        assertFalse(registry.parse("{\"tool\":\"set_reminder\",\"arguments\":{\"when_epoch_sec\":"
                + VALID_WHEN + ",\"title\":\"\",\"body\":\"x\"}}").isValid());
        assertFalse(registry.parse("{\"tool\":\"set_reminder\",\"arguments\":{\"when_epoch_sec\":100,"
                + "\"title\":\"A\",\"body\":\"B\"}}").isValid());
    }

    private static final class FakeScheduler implements ReminderScheduler {
        final List<Plan> plans = new ArrayList<>();
        boolean fail;

        @Override public Result schedule(Plan plan) {
            if (fail) return new Result(false, "REMINDER_SCHEDULE_FAILED", "failed");
            plans.add(plan);
            return new Result(true, "REMINDER_SCHEDULED", "ok");
        }

        @Override public void cancel(String workId) {
        }
    }
}