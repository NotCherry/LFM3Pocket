package com.notcherry.lfm3pocket;

import org.junit.Test;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import static org.junit.Assert.*;

public final class CurrentTimeToolTest {
    @Test public void formatsRequestedTimezoneAndRejectsUnknownZones() {
        ZonedDateTime fixed = ZonedDateTime.of(2026, 11, 16, 15, 30, 0, 0, ZoneId.of("UTC"));
        String text = CurrentTimeTool.format("Europe/Warsaw", () -> fixed);
        assertTrue(text.startsWith("UNTRUSTED_TIME"));
        assertTrue(text.contains("zone=Europe/Warsaw"));
        try {
            CurrentTimeTool.format("Not/AZone", () -> fixed);
            fail();
        } catch (IllegalArgumentException expected) { }
    }
}
