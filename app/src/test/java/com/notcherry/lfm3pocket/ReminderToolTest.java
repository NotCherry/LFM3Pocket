package com.notcherry.lfm3pocket;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public final class ReminderToolTest {
    private static final long NOW = 1_700_000_000L;

    private ToolRegistry registry() {
        return new ToolRegistry(() -> NOW);
    }

    private String reminderJson(long when, String title, String body) {
        return "{\"tool\":\"set_reminder\",\"arguments\":{\"when_epoch_sec\":" + when
                + ",\"title\":\"" + escape(title) + "\",\"body\":\"" + escape(body) + "\"}}";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Test public void parsesValidReminderWithinBounds() {
        ToolRegistry.Parsed parsed = registry().parse(reminderJson(NOW + 120, "Dentist", "Call"));
        assertTrue(parsed.message, parsed.isValid());
        assertTrue(parsed.request instanceof ToolRegistry.ReminderRequest);
        ToolRegistry.ReminderRequest req = (ToolRegistry.ReminderRequest) parsed.request;
        assertEquals(NOW + 120, req.whenEpochSec);
        assertEquals("Dentist", req.title);
        assertEquals("Call", req.body);
    }

    @Test public void acceptsBoundaryWindowAndMaxLengths() {
        String title = repeat('a', 100);
        String body = repeat('b', 500);
        assertTrue(registry().parse(reminderJson(NOW + 60, title, body)).isValid());
        assertTrue(registry().parse(reminderJson(NOW + 366L * 24L * 3600L, "t", "")).isValid());
    }

    @Test public void rejectsPastTimesAndOutOfWindowTimes() {
        assertInvalid(reminderJson(NOW - 1, "t", ""), "INVALID_ARGUMENTS");
        assertInvalid(reminderJson(NOW, "t", ""), "INVALID_ARGUMENTS");
        assertInvalid(reminderJson(NOW + 59, "t", ""), "INVALID_ARGUMENTS");
        assertInvalid(reminderJson(NOW + 366L * 24L * 3600L + 1, "t", ""), "INVALID_ARGUMENTS");
    }

    @Test public void rejectsInvalidTitleBodyAndHtml() {
        assertInvalid(reminderJson(NOW + 120, repeat('a', 101), ""), "INVALID_ARGUMENTS");
        assertInvalid(reminderJson(NOW + 120, "t", repeat('b', 501)), "INVALID_ARGUMENTS");
        assertInvalid("{\"tool\":\"set_reminder\",\"arguments\":{\"when_epoch_sec\":" + (NOW + 120)
                + ",\"title\":\"<b>x</b>\",\"body\":\"ok\"}}", "INVALID_ARGUMENTS");
        assertInvalid("{\"tool\":\"set_reminder\",\"arguments\":{\"when_epoch_sec\":" + (NOW + 120)
                + ",\"title\":\"ok\",\"body\":\"hi <script>alert(1)</script>\"}}", "INVALID_ARGUMENTS");
        assertInvalid("{\"tool\":\"set_reminder\",\"arguments\":{\"when_epoch_sec\":" + (NOW + 120)
                + ",\"title\":\"line\\nbreak\",\"body\":\"ok\"}}", "INVALID_ARGUMENTS");
    }

    @Test public void rejectsMissingOrUnexpectedArguments() {
        assertInvalid("{\"tool\":\"set_reminder\",\"arguments\":{\"when_epoch_sec\":" + (NOW + 120)
                + ",\"title\":\"t\"}}", "INVALID_ARGUMENTS");
        assertInvalid("{\"tool\":\"set_reminder\",\"arguments\":{\"when_epoch_sec\":" + (NOW + 120)
                + ",\"title\":\"t\",\"body\":\"b\",\"extra\":1}}", "INVALID_ARGUMENTS");
        assertInvalid("{\"tool\":\"set_reminder\",\"arguments\":{\"when_epoch_sec\":\"soon\",\"title\":\"t\",\"body\":\"b\"}}",
                "INVALID_ARGUMENTS");
    }

    @Test public void reminderIsConfirmRiskWithExactDescribeReadBack() {
        ToolRegistry.Parsed parsed = registry().parse(reminderJson(NOW + 3600, "Zadzwoń do dentysty", ""));
        assertTrue(parsed.isValid());
        assertEquals(ToolCoordinator.Risk.CONFIRM, ToolCoordinator.risk(parsed.request));
        String describe = ToolCoordinator.describe(parsed.request);
        assertTrue(describe.contains("Reminder"));
        assertTrue(describe.contains("when_epoch_sec: " + (NOW + 3600)));
        assertTrue(describe.contains("title: \"Zadzwoń do dentysty\""));
        assertTrue(describe.contains("body: \"\""));
    }

    @Test public void approveSchedulesExactReminderAndRejectOrExpiryScheduleNothing() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger effects = new AtomicInteger();
        ToolCoordinator coordinator = new ToolCoordinator(request -> {
            effects.incrementAndGet();
            return new ReminderActionExecutor(scheduler).execute(request.tool.wireName, toStringArgs(request));
        }, request -> true, () -> 0L);

        ToolRegistry.Request reminder = registry().parse(reminderJson(NOW + 7200, "Exact title", "Body")).request;

        ToolCoordinator.Outcome pending = coordinator.propose(reminder, ToolCoordinator.Source.MODEL_PROPOSAL);
        assertEquals("PENDING_CONFIRMATION", pending.status);
        assertEquals(0, scheduler.calls);
        ToolCoordinator.Outcome approved = coordinator.approve(pending.proposal.id);
        assertTrue(approved.result.succeeded);
        assertEquals("REMINDER_SCHEDULED", approved.result.status);
        assertEquals(1, scheduler.calls);
        assertEquals(NOW + 7200, scheduler.whenEpochSec);
        assertEquals("Exact title", scheduler.title);
        assertEquals("Body", scheduler.body);

        ToolCoordinator.Outcome rejected = coordinator.propose(reminder, ToolCoordinator.Source.MODEL_PROPOSAL);
        coordinator.reject(rejected.proposal.id);
        assertEquals(1, scheduler.calls);

        final long[] clock = {0L};
        ToolCoordinator c2 = new ToolCoordinator(request -> new ReminderActionExecutor(scheduler).execute(request.tool.wireName, toStringArgs(request)),
                request -> true, () -> clock[0]);
        ToolCoordinator.Outcome exp = c2.propose(reminder, ToolCoordinator.Source.MODEL_PROPOSAL);
        clock[0] = 60001L;
        assertEquals("EXPIRED", c2.approve(exp.proposal.id).status);
        assertEquals(1, scheduler.calls);
        assertEquals(1, effects.get()); // only first approve executed in first coordinator
    }

    @Test public void voiceInjectedConfirmTakNieDrivesReminderApproveReject() {
        FakeScheduler scheduler = new FakeScheduler();
        ToolRegistry.Request reminder = registry().parse(reminderJson(NOW + 3600, "Lek", "Tabletka")).request;

        ToolCoordinator approveCoordinator = new ToolCoordinator(request ->
                new ReminderActionExecutor(scheduler).execute(request.tool.wireName, toStringArgs(request)),
                request -> true, () -> 0L);
        ToolCoordinator.Outcome pendingApprove = approveCoordinator.propose(reminder, ToolCoordinator.Source.MODEL_PROPOSAL);
        assertEquals("PENDING_CONFIRMATION", pendingApprove.status);
        assertEquals(VoiceConfirmLexicon.Decision.APPROVE, VoiceConfirmLexicon.matchTranscript("tak"));
        assertTrue(approveCoordinator.approve(pendingApprove.proposal.id).result.succeeded);
        assertEquals(1, scheduler.calls);
        assertEquals("Lek", scheduler.title);

        ToolCoordinator rejectCoordinator = new ToolCoordinator(request ->
                new ReminderActionExecutor(scheduler).execute(request.tool.wireName, toStringArgs(request)),
                request -> true, () -> 0L);
        ToolCoordinator.Outcome pendingReject = rejectCoordinator.propose(reminder, ToolCoordinator.Source.MODEL_PROPOSAL);
        assertEquals(VoiceConfirmLexicon.Decision.REJECT, VoiceConfirmLexicon.matchTranscript("nie"));
        rejectCoordinator.reject(pendingReject.proposal.id);
        assertEquals(1, scheduler.calls);
    }

    @Test public void voiceInjectedConfirmEnglishYesNoDrivesReminderApproveReject() {
        FakeScheduler scheduler = new FakeScheduler();
        ToolRegistry.Request reminder = registry().parse(reminderJson(NOW + 3600, "Call", "")).request;

        ToolCoordinator approveCoordinator = new ToolCoordinator(request ->
                new ReminderActionExecutor(scheduler).execute(request.tool.wireName, toStringArgs(request)),
                request -> true, () -> 0L);
        ToolCoordinator.Outcome pendingApprove = approveCoordinator.propose(reminder, ToolCoordinator.Source.MODEL_PROPOSAL);
        assertEquals(VoiceConfirmLexicon.Decision.APPROVE, VoiceConfirmLexicon.matchTranscript("yes"));
        approveCoordinator.approve(pendingApprove.proposal.id);
        assertEquals(1, scheduler.calls);

        ToolCoordinator rejectCoordinator = new ToolCoordinator(request ->
                new ReminderActionExecutor(scheduler).execute(request.tool.wireName, toStringArgs(request)),
                request -> true, () -> 0L);
        ToolCoordinator.Outcome pendingReject = rejectCoordinator.propose(reminder, ToolCoordinator.Source.MODEL_PROPOSAL);
        assertEquals(VoiceConfirmLexicon.Decision.REJECT, VoiceConfirmLexicon.matchTranscript("no"));
        rejectCoordinator.reject(pendingReject.proposal.id);
        assertEquals(1, scheduler.calls);
    }

    @Test public void actionExecutorRequiresExactValidatedArguments() {
        FakeScheduler scheduler = new FakeScheduler();
        ReminderActionExecutor executor = new ReminderActionExecutor(scheduler);

        assertEquals("UNKNOWN_TOOL", executor.execute("set_timer", Collections.emptyMap()).status);
        assertEquals("INVALID_ARGUMENTS", executor.execute("set_reminder", Collections.emptyMap()).status);
        Map<String, String> args = new LinkedHashMap<>();
        args.put("when_epoch_sec", String.valueOf(NOW + 120));
        args.put("title", "t");
        args.put("body", "b");
        args.put("extra", "x");
        assertEquals("INVALID_ARGUMENTS", executor.execute("set_reminder", args).status);
        assertEquals(0, scheduler.calls);
    }

    private static void assertInvalid(String json, String status) {
        ToolRegistry.Parsed parsed = new ToolRegistry(() -> NOW).parse(json);
        assertFalse("expected invalid: " + json, parsed.isValid());
        assertEquals(status, parsed.status);
    }

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }

    private static Map<String, String> toStringArgs(ToolRegistry.Request request) {
        Map<String, String> args = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : request.arguments.entrySet()) {
            Object v = e.getValue();
            args.put(e.getKey(), v == null ? "" : String.valueOf(v));
        }
        return args;
    }

    private static final class FakeScheduler implements ReminderScheduler {
        int calls;
        long whenEpochSec;
        String title;
        String body;

        @Override public AndroidActionExecutor.ActionResult schedule(long whenEpochSec, String title, String body) {
            calls++;
            this.whenEpochSec = whenEpochSec;
            this.title = title;
            this.body = body;
            return new AndroidActionExecutor.ActionResult(true, "REMINDER_SCHEDULED", "Reminder scheduled: \"" + title + "\".");
        }
    }
}
