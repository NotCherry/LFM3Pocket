package com.notcherry.lfm3pocket;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public final class CalendarToolsTest {
    private final ToolRegistry registry = new ToolRegistry();

    @Test public void describeIncludesTitleAndLocalTimeForCalendarWrites() {
        ToolRegistry.Request create = registry.parse(
                "{\"tool\":\"create_event\",\"arguments\":{\"title\":\"Standup\",\"start_epoch_sec\":1731744000,\"end_epoch_sec\":1731745800,\"calendar_id\":\"3\",\"all_day\":false,\"location\":\"\",\"notes\":\"\"}}"
        ).request;
        String createDesc = ToolCoordinator.describe(create);
        assertTrue(createDesc.contains("Standup"));
        assertTrue(createDesc.contains("title (local read-back): Standup"));
        assertTrue(createDesc.contains("time (local):"));

        ToolRegistry.Request update = registry.parse(
                "{\"tool\":\"update_event\",\"arguments\":{\"event_id\":\"42\",\"title\":\"Standup moved\",\"start_epoch_sec\":1731745800,\"end_epoch_sec\":1731747600}}"
        ).request;
        String updateDesc = ToolCoordinator.describe(update);
        assertTrue(updateDesc.contains("Standup moved"));
        assertTrue(updateDesc.contains("title (local read-back): Standup moved"));
        assertTrue(updateDesc.contains("time (local):"));

        ToolRegistry.Request list = registry.parse(
                "{\"tool\":\"list_events\",\"arguments\":{\"window_start_epoch_sec\":1731700000,\"window_end_epoch_sec\":1731786400,\"limit\":20}}"
        ).request;
        assertTrue(ToolCoordinator.describe(list).contains("window (local):"));
    }

    @Test public void writesBlockedWithoutPickedCalendarOrPermission() {
        AtomicBoolean writePermission = new AtomicBoolean(true);
        AtomicReference<String> picked = new AtomicReference<>("");
        ToolCoordinator.PermissionPolicy policy = new ToolCoordinator.PermissionPolicy() {
            @Override public boolean allowed(ToolRegistry.Request request) {
                if (request.tool == ToolRegistry.Tool.CREATE_EVENT
                        || request.tool == ToolRegistry.Tool.UPDATE_EVENT
                        || request.tool == ToolRegistry.Tool.DELETE_EVENT) {
                    if (!writePermission.get()) return false;
                    String p = picked.get();
                    if (p == null || p.trim().isEmpty()) return false;
                    if (request instanceof ToolRegistry.CreateEventRequest) {
                        return p.equals(((ToolRegistry.CreateEventRequest) request).calendarId);
                    }
                    return true;
                }
                return true;
            }
            @Override public String denialMessage(ToolRegistry.Request request) {
                if (!writePermission.get()) return "Grant Calendar permission in Android app settings to change events.";
                String p = picked.get();
                if (p == null || p.trim().isEmpty()) return "Pick a calendar in Settings before changing events.";
                return "Permission denied";
            }
        };
        ToolCoordinator coordinator = new ToolCoordinator(
                request -> new AndroidActionExecutor.ActionResult(true, "OK", "ok"),
                policy,
                () -> 0L);

        ToolRegistry.Request create = registry.parse(
                "{\"tool\":\"create_event\",\"arguments\":{\"title\":\"A\",\"start_epoch_sec\":10,\"end_epoch_sec\":20,\"calendar_id\":\"3\",\"all_day\":false,\"location\":\"\",\"notes\":\"\"}}"
        ).request;
        ToolCoordinator.Outcome noPick = coordinator.propose(create, ToolCoordinator.Source.MODEL_PROPOSAL);
        assertEquals("PERMISSION_DENIED", noPick.status);
        assertTrue(noPick.result.message.contains("Pick a calendar"));

        picked.set("3");
        writePermission.set(false);
        ToolCoordinator.Outcome noPerm = coordinator.propose(create, ToolCoordinator.Source.MODEL_PROPOSAL);
        assertEquals("PERMISSION_DENIED", noPerm.status);
        assertTrue(noPerm.result.message.contains("Grant Calendar permission"));
    }

    @Test public void revokeBetweenProposeAndApproveFailsClosed() {
        AtomicBoolean writePermission = new AtomicBoolean(true);
        ToolCoordinator.PermissionPolicy policy = new ToolCoordinator.PermissionPolicy() {
            @Override public boolean allowed(ToolRegistry.Request request) {
                if (request.tool == ToolRegistry.Tool.CREATE_EVENT) return writePermission.get();
                return true;
            }
            @Override public String denialMessage(ToolRegistry.Request request) {
                return "Grant Calendar permission in Android app settings to change events.";
            }
        };
        final List<String> effects = new ArrayList<>();
        ToolCoordinator coordinator = new ToolCoordinator(request -> {
            effects.add("executed");
            return new AndroidActionExecutor.ActionResult(true, "EVENT_CREATED", "created");
        }, policy, new AtomicLong()::get);

        ToolRegistry.Request create = registry.parse(
                "{\"tool\":\"create_event\",\"arguments\":{\"title\":\"A\",\"start_epoch_sec\":10,\"end_epoch_sec\":20,\"calendar_id\":\"3\",\"all_day\":false,\"location\":\"\",\"notes\":\"\"}}"
        ).request;
        ToolCoordinator.Outcome pending = coordinator.propose(create, ToolCoordinator.Source.MODEL_PROPOSAL);
        assertEquals("PENDING_CONFIRMATION", pending.status);

        writePermission.set(false);
        ToolCoordinator.Outcome approved = coordinator.approve(pending.proposal.id);
        assertEquals("PERMISSION_DENIED", approved.status);
        assertFalse(approved.result.succeeded);
        assertTrue(effects.isEmpty());
    }

    @Test public void staleEventIdFailsClosedThroughExecutor() {
        FakeCalendarGateway gateway = new FakeCalendarGateway();
        gateway.staleIds.add("42");
        CalendarActionExecutor executor = new CalendarActionExecutor(gateway);

        AndroidActionExecutor.ActionResult update = executor.execute("update_event", map(
                "event_id", "42",
                "title", "Moved",
                "start_epoch_sec", "100",
                "end_epoch_sec", "200"));
        assertFalse(update.succeeded);
        assertEquals("STALE_EVENT", update.status);

        AndroidActionExecutor.ActionResult delete = executor.execute("delete_event", map("event_id", "42"));
        assertFalse(delete.succeeded);
        assertEquals("STALE_EVENT", delete.status);
        assertTrue(gateway.calls.isEmpty());
    }

    @Test public void calendarWritesRequirePickedCalendarInGatewayAndResultsAreUntrusted() {
        FakeCalendarGateway gateway = new FakeCalendarGateway();
        gateway.pickedCalendarId = "";
        AndroidActionExecutor.ActionResult blockedCreate = gateway.createEvent("A", 10, 20, "3", false, "", "");
        assertFalse(blockedCreate.succeeded);
        assertEquals("PERMISSION_DENIED", blockedCreate.status);

        gateway.pickedCalendarId = "3";
        gateway.staleIds.clear();
        AndroidActionExecutor.ActionResult created = gateway.createEvent("A", 10, 20, "3", false, "", "");
        assertTrue(created.succeeded);
        assertTrue(created.message.contains("UNTRUSTED_CALENDAR"));

        AndroidActionExecutor.ActionResult listed = gateway.listEvents(0, 100, 10);
        assertTrue(listed.succeeded);
        assertTrue(listed.message.startsWith("UNTRUSTED_CALENDAR"));

        AndroidActionExecutor.ActionResult updated = gateway.updateEvent("7", "B", 30, 40);
        assertTrue(updated.succeeded);
        assertTrue(updated.message.contains("UNTRUSTED_CALENDAR"));
    }

    private static java.util.Map<String, String> map(String... kv) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) out.put(kv[i], kv[i + 1]);
        return out;
    }

    private static final class FakeCalendarGateway implements CalendarGateway {
        String pickedCalendarId = "3";
        final java.util.Set<String> staleIds = new java.util.HashSet<>();
        final List<String> calls = new ArrayList<>();

        @Override
        public AndroidActionExecutor.ActionResult listEvents(long windowStartEpochSec, long windowEndEpochSec, int limit) {
            calls.add("list");
            return new AndroidActionExecutor.ActionResult(true, "LIST_EVENTS",
                    "UNTRUSTED_CALENDAR events\n- id=1 title=\"Test\" start=2024-01-01 00:00 UTC end=2024-01-01 01:00 UTC all_day=false");
        }

        @Override
        public AndroidActionExecutor.ActionResult createEvent(String title, long startEpochSec, long endEpochSec,
                                                              String calendarId, boolean allDay, String location, String notes) {
            if (pickedCalendarId == null || pickedCalendarId.trim().isEmpty()) {
                return new AndroidActionExecutor.ActionResult(false, "PERMISSION_DENIED",
                        "Pick a calendar in Settings before creating events.");
            }
            if (calendarId == null || !pickedCalendarId.equals(calendarId)) {
                return new AndroidActionExecutor.ActionResult(false, "PERMISSION_DENIED",
                        "Event must target the calendar picked in Settings.");
            }
            calls.add("create:" + title);
            return new AndroidActionExecutor.ActionResult(true, "EVENT_CREATED",
                    "UNTRUSTED_CALENDAR created event_id=99 title=\"" + title + "\"");
        }

        @Override
        public AndroidActionExecutor.ActionResult updateEvent(String eventId, String title, long startEpochSec, long endEpochSec) {
            if (pickedCalendarId == null || pickedCalendarId.trim().isEmpty()) {
                return new AndroidActionExecutor.ActionResult(false, "PERMISSION_DENIED",
                        "Pick a calendar in Settings before updating events.");
            }
            if (staleIds.contains(eventId)) {
                return new AndroidActionExecutor.ActionResult(false, "STALE_EVENT",
                        "Calendar event was not found; it may be stale or deleted.");
            }
            calls.add("update:" + eventId);
            return new AndroidActionExecutor.ActionResult(true, "EVENT_UPDATED",
                    "UNTRUSTED_CALENDAR updated event_id=" + eventId + " title=\"" + title + "\"");
        }

        @Override
        public AndroidActionExecutor.ActionResult deleteEvent(String eventId) {
            if (pickedCalendarId == null || pickedCalendarId.trim().isEmpty()) {
                return new AndroidActionExecutor.ActionResult(false, "PERMISSION_DENIED",
                        "Pick a calendar in Settings before deleting events.");
            }
            if (staleIds.contains(eventId)) {
                return new AndroidActionExecutor.ActionResult(false, "STALE_EVENT",
                        "Calendar event was not found; it may be stale or deleted.");
            }
            calls.add("delete:" + eventId);
            return new AndroidActionExecutor.ActionResult(true, "EVENT_DELETED",
                    "UNTRUSTED_CALENDAR deleted event_id=" + eventId);
        }
    }
}
