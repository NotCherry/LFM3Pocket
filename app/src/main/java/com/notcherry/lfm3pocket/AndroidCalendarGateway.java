package com.notcherry.lfm3pocket;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Supplier;

/** CalendarContract-backed calendar tools. Returned event data is untrusted. */
public final class AndroidCalendarGateway implements CalendarGateway {
    private final Context context;
    private final Supplier<String> pickedCalendarId;

    public AndroidCalendarGateway(Context context, Supplier<String> pickedCalendarId) {
        if (context == null || pickedCalendarId == null) throw new IllegalArgumentException("Missing calendar dependencies");
        Context app = context.getApplicationContext();
        this.context = app == null ? context : app;
        this.pickedCalendarId = pickedCalendarId;
    }

    private Context context() { return context; }

    private ContentResolver resolver() { return context().getContentResolver(); }

    @Override
    public AndroidActionExecutor.ActionResult listEvents(long windowStartEpochSec, long windowEndEpochSec, int limit) {
        Uri uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .appendPath(String.valueOf(windowStartEpochSec * 1000L))
                .appendPath(String.valueOf(windowEndEpochSec * 1000L))
                .build();
        String[] projection = {
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY
        };
        String sort = CalendarContract.Instances.BEGIN + " ASC";
        StringBuilder out = new StringBuilder("UNTRUSTED_CALENDAR events");
        int count = 0;
        try (Cursor cursor = resolver().query(uri, projection, null, null, sort)) {
            if (cursor == null) {
                return fail("CALENDAR_UNAVAILABLE", "Calendar provider is unavailable.");
            }
            int idIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_ID);
            int titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE);
            int beginIdx = cursor.getColumnIndex(CalendarContract.Instances.BEGIN);
            int endIdx = cursor.getColumnIndex(CalendarContract.Instances.END);
            int allDayIdx = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY);
            while (cursor.moveToNext() && count < limit) {
                String eventId = idIdx >= 0 ? safeString(cursor, idIdx) : "";
                String title = titleIdx >= 0 ? safeString(cursor, titleIdx) : "";
                long beginMs = beginIdx >= 0 && !cursor.isNull(beginIdx) ? cursor.getLong(beginIdx) : 0L;
                long endMs = endIdx >= 0 && !cursor.isNull(endIdx) ? cursor.getLong(endIdx) : 0L;
                boolean allDay = allDayIdx >= 0 && !cursor.isNull(allDayIdx) && cursor.getInt(allDayIdx) != 0;
                out.append('\n').append("- id=").append(sanitize(eventId))
                        .append(" title=").append(quote(sanitize(title)))
                        .append(" start=").append(formatMs(beginMs, allDay))
                        .append(" end=").append(formatMs(endMs, allDay))
                        .append(" all_day=").append(allDay);
                count++;
            }
        } catch (SecurityException e) {
            return fail("PERMISSION_DENIED", "Calendar permission is required to list events.");
        } catch (Exception e) {
            return fail("CALENDAR_READ_FAILED", "Could not read calendar events.");
        }
        if (count == 0) out.append("\n(none)");
        return ok("LIST_EVENTS", out.toString());
    }

    @Override
    public AndroidActionExecutor.ActionResult createEvent(String title, long startEpochSec, long endEpochSec,
                                                          String calendarId, boolean allDay, String location, String notes) {
        String picked = normalize(pickedCalendarId.get());
        if (picked.isEmpty()) {
            return fail("PERMISSION_DENIED", "Pick a calendar in Settings before creating events.");
        }
        if (calendarId == null || !picked.equals(calendarId)) {
            return fail("PERMISSION_DENIED", "Event must target the calendar picked in Settings.");
        }
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID, parseCalendarId(picked));
        values.put(CalendarContract.Events.TITLE, title == null ? "" : title);
        values.put(CalendarContract.Events.DTSTART, startEpochSec * 1000L);
        values.put(CalendarContract.Events.DTEND, endEpochSec * 1000L);
        values.put(CalendarContract.Events.ALL_DAY, allDay ? 1 : 0);
        values.put(CalendarContract.Events.EVENT_LOCATION, location == null ? "" : location);
        values.put(CalendarContract.Events.DESCRIPTION, notes == null ? "" : notes);
        values.put(CalendarContract.Events.EVENT_TIMEZONE,
                allDay ? "UTC" : ZoneId.systemDefault().getId());
        try {
            Uri uri = resolver().insert(CalendarContract.Events.CONTENT_URI, values);
            if (uri == null) return fail("CALENDAR_WRITE_FAILED", "Calendar provider did not create the event.");
            long id = ContentUris.parseId(uri);
            if (id <= 0) return fail("CALENDAR_WRITE_FAILED", "Calendar provider returned an invalid event id.");
            return ok("EVENT_CREATED", "UNTRUSTED_CALENDAR created event_id=" + id
                    + " title=" + quote(sanitize(title))
                    + " start=" + formatSec(startEpochSec, allDay)
                    + " end=" + formatSec(endEpochSec, allDay));
        } catch (SecurityException e) {
            return fail("PERMISSION_DENIED", "Calendar write permission is required.");
        } catch (IllegalArgumentException e) {
            return fail("INVALID_ARGUMENTS", "Calendar target is invalid.");
        } catch (Exception e) {
            return fail("CALENDAR_WRITE_FAILED", "Could not create the calendar event.");
        }
    }

    @Override
    public AndroidActionExecutor.ActionResult updateEvent(String eventId, String title, long startEpochSec, long endEpochSec) {
        String picked = normalize(pickedCalendarId.get());
        if (picked.isEmpty()) {
            return fail("PERMISSION_DENIED", "Pick a calendar in Settings before updating events.");
        }
        long id = parseEventId(eventId);
        if (id <= 0) return fail("INVALID_ARGUMENTS", "Event id is invalid.");
        if (!eventExists(id)) return fail("STALE_EVENT", "Calendar event was not found; it may be stale or deleted.");
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.TITLE, title == null ? "" : title);
        values.put(CalendarContract.Events.DTSTART, startEpochSec * 1000L);
        values.put(CalendarContract.Events.DTEND, endEpochSec * 1000L);
        values.put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().getId());
        try {
            Uri uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id);
            int updated = resolver().update(uri, values, null, null);
            if (updated <= 0) return fail("STALE_EVENT", "Calendar event could not be updated; it may be stale.");
            return ok("EVENT_UPDATED", "UNTRUSTED_CALENDAR updated event_id=" + id
                    + " title=" + quote(sanitize(title))
                    + " start=" + formatSec(startEpochSec, false)
                    + " end=" + formatSec(endEpochSec, false));
        } catch (SecurityException e) {
            return fail("PERMISSION_DENIED", "Calendar write permission is required.");
        } catch (Exception e) {
            return fail("CALENDAR_WRITE_FAILED", "Could not update the calendar event.");
        }
    }

    @Override
    public AndroidActionExecutor.ActionResult deleteEvent(String eventId) {
        String picked = normalize(pickedCalendarId.get());
        if (picked.isEmpty()) {
            return fail("PERMISSION_DENIED", "Pick a calendar in Settings before deleting events.");
        }
        long id = parseEventId(eventId);
        if (id <= 0) return fail("INVALID_ARGUMENTS", "Event id is invalid.");
        if (!eventExists(id)) return fail("STALE_EVENT", "Calendar event was not found; it may be stale or deleted.");
        try {
            Uri uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id);
            int deleted = resolver().delete(uri, null, null);
            if (deleted <= 0) return fail("STALE_EVENT", "Calendar event could not be deleted; it may be stale.");
            return ok("EVENT_DELETED", "UNTRUSTED_CALENDAR deleted event_id=" + id);
        } catch (SecurityException e) {
            return fail("PERMISSION_DENIED", "Calendar write permission is required.");
        } catch (Exception e) {
            return fail("CALENDAR_WRITE_FAILED", "Could not delete the calendar event.");
        }
    }

    private boolean eventExists(long id) {
        Uri uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id);
        String[] projection = { CalendarContract.Events._ID };
        try (Cursor cursor = resolver().query(uri, projection, null, null, null)) {
            return cursor != null && cursor.moveToFirst();
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            return false;
        }
    }

    private static String safeString(Cursor cursor, int idx) {
        try {
            String value = cursor.getString(idx);
            return value == null ? "" : value;
        } catch (Exception e) {
            return "";
        }
    }

    private static String normalize(String value) { return value == null ? "" : value.trim(); }

    private static long parseEventId(String raw) {
        try { return Long.parseLong(normalize(raw)); } catch (Exception e) { return -1L; }
    }

    private static long parseCalendarId(String raw) {
        try { return Long.parseLong(normalize(raw)); } catch (Exception e) { throw new IllegalArgumentException("invalid calendar id"); }
    }

    private static String formatSec(long epochSec, boolean allDay) { return formatMs(epochSec * 1000L, allDay); }

    private static String formatMs(long epochMs, boolean allDay) {
        try {
            Instant instant = Instant.ofEpochMilli(epochMs);
            if (allDay) {
                return instant.atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)) + " (all-day)";
            }
            return instant.atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.ROOT));
        } catch (Exception e) {
            return String.valueOf(epochMs);
        }
    }

    private static String sanitize(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\t') continue;
            if (c == '\n' || c == '\r') { out.append(' '); continue; }
            out.append(c);
        }
        return out.toString();
    }

    private static String quote(String text) { return '"' + (text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"")) + '"'; }

    private static AndroidActionExecutor.ActionResult ok(String status, String message) {
        return new AndroidActionExecutor.ActionResult(true, status, message);
    }

    private static AndroidActionExecutor.ActionResult fail(String status, String message) {
        return new AndroidActionExecutor.ActionResult(false, status, message);
    }
}
