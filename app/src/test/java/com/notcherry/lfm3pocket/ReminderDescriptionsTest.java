package com.notcherry.lfm3pocket;

import org.junit.Test;

import java.time.ZoneId;
import java.util.Locale;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ReminderDescriptionsTest {
    @Test public void formatLocalAvoidsRawEpochInConfirmText() {
        String formatted = ReminderDescriptions.formatLocal(1_700_000_600L, Locale.US, ZoneId.of("UTC"));
        assertFalse(formatted.matches(".*1700000600.*"));
        assertTrue(formatted.length() > 4);
    }
}