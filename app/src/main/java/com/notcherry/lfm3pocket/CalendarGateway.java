package com.notcherry.lfm3pocket;

/** Calendar operations behind a small seam so JVM tests can fake ContentResolver behavior. */
public interface CalendarGateway {
    AndroidActionExecutor.ActionResult listEvents(long windowStartEpochSec, long windowEndEpochSec, int limit);

    AndroidActionExecutor.ActionResult createEvent(String title, long startEpochSec, long endEpochSec,
                                                   String calendarId, boolean allDay, String location, String notes);

    AndroidActionExecutor.ActionResult updateEvent(String eventId, String title, long startEpochSec, long endEpochSec);

    AndroidActionExecutor.ActionResult deleteEvent(String eventId);
}
