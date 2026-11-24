package com.notcherry.lfm3pocket;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CrashLogTest {
    @Test public void recordsUncaughtExceptionLocallyWithoutNetworkHints() throws Exception {
        File dir = Files.createTempDirectory("hanami-crash").toFile();
        File log = new File(dir, CrashLog.FILE_NAME);
        try {
            CrashLog.recordUncaught(log, Thread.currentThread(), new IllegalStateException("boom"));
            String body = CrashLog.readFile(log);
            assertTrue(body.contains("uncaught"));
            assertTrue(body.contains("IllegalStateException"));
            assertTrue(body.contains("boom"));
            assertFalse(body.contains("http://"));
            assertFalse(body.contains("https://"));
            CrashLog.clearFile(log);
            assertTrue(CrashLog.readFile(log).isEmpty());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            log.delete();
            //noinspection ResultOfMethodCallIgnored
            dir.delete();
        }
    }

    @Test public void deduplicatesHistoricalProcessExitsByTimestamp() throws Exception {
        File dir = Files.createTempDirectory("hanami-crash-dedup").toFile();
        File log = new File(dir, CrashLog.FILE_NAME);
        try {
            CrashLog.ExitEntry older = new CrashLog.ExitEntry("CRASH", 0, 300, 1_000L, "crash", null);
            CrashLog.ExitEntry newer = new CrashLog.ExitEntry("USER_REQUESTED", 0, 400, 2_000L, "remove task", null);

            long wm1 = CrashLog.recordNewExits(log, 0L, Arrays.asList(newer, older));
            assertEquals(2_000L, wm1);
            String afterFirst = CrashLog.readFile(log);
            assertEquals(1, countOccurrences(afterFirst, "--- process exits @"));
            assertEquals(1, countOccurrences(afterFirst, "timestamp=1000"));
            assertEquals(1, countOccurrences(afterFirst, "timestamp=2000"));

            // Re-running with the same historical list must be a complete no-op.
            long wm2 = CrashLog.recordNewExits(log, wm1, Arrays.asList(newer, older));
            assertEquals(2_000L, wm2);
            assertEquals(afterFirst, CrashLog.readFile(log));

            // A subsequent launch with one brand-new exit appends ONLY that new entry.
            CrashLog.ExitEntry latest = new CrashLog.ExitEntry("OTHER", 0, 400, 3_000L, "empty #31", null);
            long wm3 = CrashLog.recordNewExits(log, wm2, Arrays.asList(latest, newer, older));
            assertEquals(3_000L, wm3);
            String afterThird = CrashLog.readFile(log);
            assertEquals(2, countOccurrences(afterThird, "--- process exits @"));
            assertEquals(1, countOccurrences(afterThird, "timestamp=3000"));
            assertEquals(1, countOccurrences(afterThird, "timestamp=2000"));
            assertEquals(1, countOccurrences(afterThird, "timestamp=1000"));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            log.delete();
            //noinspection ResultOfMethodCallIgnored
            dir.delete();
        }
    }

    @Test public void maxLoggedExitTimestampSeedsWatermarkFromExistingLog() {
        String existing = "--- process exits @ 2026-11-24T16:54:54Z ---\n"
                + "reason=OTHER status=0 importance=400 timestamp=1795460519631 description=empty #31\n"
                + "reason=CRASH status=0 importance=300 timestamp=1794925161044 description=crash\n";
        assertEquals(1795460519631L, CrashLog.maxLoggedExitTimestamp(existing));
        assertEquals(0L, CrashLog.maxLoggedExitTimestamp(""));
        assertEquals(0L, CrashLog.maxLoggedExitTimestamp("--- uncaught @ 2026-11-17 ---\n"));
        assertEquals(500L, CrashLog.recordNewExits(null, 500L, Collections.emptyList()));
    }

    private static int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
