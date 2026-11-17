package com.notcherry.lfm3pocket;

import org.junit.Test;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class ReminderTimeParserTest {
    @Test public void inMinutesAddsLeadFromNow() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("in_minutes", 10);
        long now = 1_700_000_000L;
        assertEquals(now + 600L, ReminderTimeParser.toEpochSec(args, now, ZoneId.of("UTC")));
    }

    @Test public void localAtParsesInZone() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("at", "2024-06-15T09:30");
        long now = 1_700_000_000L;
        ZoneId zone = ZoneId.of("UTC");
        long expected = java.time.LocalDateTime.parse("2024-06-15T09:30").atZone(zone).toEpochSecond();
        assertEquals(expected, ReminderTimeParser.toEpochSec(args, now, zone));
    }

    @Test public void rejectsBothTimeFields() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("in_minutes", 5);
        args.put("at", "2030-06-15T09:30");
        assertThrows(IllegalArgumentException.class,
                () -> ReminderTimeParser.toEpochSec(args, 1_700_000_000L, ZoneId.of("UTC")));
    }
}