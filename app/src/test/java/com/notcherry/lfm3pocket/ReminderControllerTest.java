package com.notcherry.lfm3pocket;

import org.junit.After;
import org.junit.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ReminderControllerTest {
    private static final long NOW = 1_700_000_000L;
    private static final long VALID_WHEN = NOW + 600L;
    private static final ZoneId TEST_ZONE = ZoneId.of("UTC");

    @After public void resetReminderParseClock() {
        ToolRegistry.resetReminderParseClockForTests();
    }

    @Test public void schedulesFutureReminderWithExactTitleAndBody() {
        FakeScheduler scheduler = new FakeScheduler();
        FakeBook book = new FakeBook();
        ReminderController controller = new ReminderController(scheduler, book, () -> NOW);
        AndroidActionExecutor.ActionResult result = controller.request(VALID_WHEN, "Buy milk", "2%");

        assertTrue(result.succeeded);
        assertEquals("REMINDER_SCHEDULED", result.status);
        assertEquals(1, scheduler.plans.size());
        assertEquals("Buy milk", scheduler.plans.get(0).title);
        assertTrue(result.message.contains("Buy milk"));
    }

    @Test public void rejectsDuplicateReminder() {
        FakeScheduler scheduler = new FakeScheduler();
        FakeBook book = new FakeBook();
        book.duplicate = true;
        ReminderController controller = new ReminderController(scheduler, book, () -> NOW);
        assertEquals("REMINDER_DUPLICATE", controller.request(VALID_WHEN, "Tea", "").status);
        assertEquals(0, scheduler.plans.size());
    }

    @Test public void androidToolsExecutorDispatchesReminderAndTimerStaysInTimerSlot() {
        FakeScheduler scheduler = new FakeScheduler();
        FakeBook book = new FakeBook();
        AndroidActionExecutor reminder = new ReminderActionExecutor(new ReminderController(scheduler, book, () -> NOW));
        AndroidActionExecutor timerStub = (name, arguments) ->
                new AndroidActionExecutor.ActionResult(false, "UNKNOWN_TOOL", "This Android action is not enabled.");
        AndroidToolsExecutor dispatcher = new AndroidToolsExecutor(timerStub, timerStub, timerStub, timerStub,
                reminder, timerStub, timerStub, timerStub);
        Map<String, String> args = new LinkedHashMap<>();
        args.put("when_epoch_sec", String.valueOf(VALID_WHEN));
        args.put("title", "Stand");
        args.put("body", "Stretch");

        assertEquals("REMINDER_SCHEDULED", dispatcher.execute("set_reminder", args).status);
        assertEquals("UNKNOWN_TOOL", dispatcher.execute("set_timer", args).status);
    }

    @Test public void voiceConfirmApproveSchedulesAndRejectHasNoSideEffect() {
        ToolRegistry.setReminderParseClockForTests(() -> NOW, TEST_ZONE);
        FakeScheduler scheduler = new FakeScheduler();
        FakeBook book = new FakeBook();
        ToolCoordinator.Handler handler = request -> {
            if (request instanceof ToolRegistry.ReminderRequest reminder) {
                return new ReminderActionExecutor(new ReminderController(scheduler, book, () -> NOW))
                        .execute("set_reminder", Map.of(
                                "when_epoch_sec", String.valueOf(reminder.whenEpochSec),
                                "title", reminder.title,
                                "body", reminder.body));
            }
            return new AndroidActionExecutor.ActionResult(false, "NOT_IMPLEMENTED", "");
        };
        ToolCoordinator coordinator = new ToolCoordinator(handler, request -> true, () -> 0L);
        String json = "{\"tool\":\"set_reminder\",\"arguments\":{\"in_minutes\":30,\"title\":\"Water\",\"body\":\"Drink\"}}";
        ToolRegistry.Parsed parsed = new ToolRegistry().parse(json);
        assertTrue(parsed.isValid());
        assertEquals(ToolCoordinator.Risk.CONFIRM, ToolCoordinator.risk(parsed.request));
        String describe = ToolCoordinator.describe(parsed.request);
        assertFalse(describe.contains("when_epoch_sec"));
        assertTrue(describe.contains("when:"));
        assertTrue(describe.contains("(UTC)"));

        ToolCoordinator.Outcome pending = coordinator.propose(parsed.request, ToolCoordinator.Source.MODEL_PROPOSAL);
        assertEquals("PENDING_CONFIRMATION", pending.status);
        coordinator.reject(pending.proposal.id);
        ToolCoordinator.Outcome retry = coordinator.propose(parsed.request, ToolCoordinator.Source.MODEL_PROPOSAL);
        assertTrue(coordinator.approve(retry.proposal.id).result.succeeded);
        assertEquals(1, scheduler.plans.size());
        assertEquals(NOW + 30L * 60L, scheduler.plans.get(0).whenEpochSec);
    }

    @Test public void expiryAndPermissionDenialHaveNoScheduleSideEffects() {
        ToolRegistry.setReminderParseClockForTests(() -> NOW, TEST_ZONE);
        FakeScheduler scheduler = new FakeScheduler();
        FakeBook book = new FakeBook();
        ToolCoordinator coordinator = new ToolCoordinator(
                request -> new ReminderActionExecutor(new ReminderController(scheduler, book, () -> NOW))
                        .execute("set_reminder", Map.of(
                                "when_epoch_sec", String.valueOf(((ToolRegistry.ReminderRequest) request).whenEpochSec),
                                "title", "Tea", "body", "")),
                request -> false, () -> 0L);
        ToolRegistry.Request reminder = new ToolRegistry().parse(
                "{\"tool\":\"set_reminder\",\"arguments\":{\"in_minutes\":60,\"title\":\"Tea\",\"body\":\"\"}}").request;
        assertEquals("PERMISSION_DENIED",
                coordinator.propose(reminder, ToolCoordinator.Source.MODEL_PROPOSAL).status);
        assertEquals(0, scheduler.plans.size());

        AtomicLong clock = new AtomicLong();
        ToolCoordinator timing = new ToolCoordinator(
                request -> new AndroidActionExecutor.ActionResult(true, "DONE", ""),
                request -> true, clock::get);
        ToolCoordinator.Outcome wait = timing.propose(reminder, ToolCoordinator.Source.MODEL_PROPOSAL);
        clock.addAndGet(60_001);
        assertEquals("EXPIRED", timing.approve(wait.proposal.id).status);
    }

    @Test public void registryAcceptsInMinutesOrLocalAt() {
        ToolRegistry.setReminderParseClockForTests(() -> NOW, TEST_ZONE);
        ToolRegistry registry = new ToolRegistry();
        assertTrue(registry.parse("{\"tool\":\"set_reminder\",\"arguments\":{"
                + "\"in_minutes\":45,\"title\":\"Call\",\"body\":\"Mom\"}}").isValid());
        assertTrue(registry.parse("{\"tool\":\"set_reminder\",\"arguments\":{"
                + "\"at\":\"2024-06-15T10:00\",\"title\":\"Call\",\"body\":\"Mom\"}}").isValid());
        assertFalse(registry.parse("{\"tool\":\"set_reminder\",\"arguments\":{"
                + "\"in_minutes\":45,\"at\":\"2024-06-15T10:00\",\"title\":\"X\",\"body\":\"\"}}").isValid());
        assertFalse(registry.parse("{\"tool\":\"set_reminder\",\"arguments\":{"
                + "\"in_minutes\":0,\"title\":\"A\",\"body\":\"\"}}").isValid());
    }

    private static final class FakeScheduler implements ReminderScheduler {
        final List<Plan> plans = new ArrayList<>();

        @Override public Result schedule(Plan plan) {
            plans.add(plan);
            return new Result(true, "REMINDER_SCHEDULED",
                    "Reminder \"" + plan.title + "\" scheduled.");
        }

        @Override public void cancel(String workId) {
        }
    }

    private static final class FakeBook implements ReminderBook {
        boolean duplicate;
        final Map<String, ReminderStore.Entry> entries = new HashMap<>();

        @Override public boolean hasDuplicate(String title, long whenEpochSec) {
            return duplicate;
        }

        @Override public List<ReminderStore.Entry> findByTitle(String title) {
            List<ReminderStore.Entry> matches = new ArrayList<>();
            for (ReminderStore.Entry entry : entries.values()) {
                if (title.equals(entry.title)) matches.add(entry);
            }
            return matches;
        }

        @Override public ReminderStore.Entry findById(String id) {
            return entries.get(id);
        }

        @Override public void put(ReminderStore.Entry entry) {
            entries.put(entry.id, entry);
        }

        @Override public void remove(String id) {
            entries.remove(id);
        }
    }
}