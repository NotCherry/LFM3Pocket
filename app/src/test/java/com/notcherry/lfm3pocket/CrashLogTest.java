package com.notcherry.lfm3pocket;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

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
}
