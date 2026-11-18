package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

public final class HistoryMediaStoreTest {
    @Test public void copyFileReplacesDestinationWithCompleteBytes() throws Exception {
        File dir = Files.createTempDirectory("history-media-copy").toFile();
        File source = new File(dir, "source.img");
        File destination = new File(dir, "destination.img");
        byte[] expected = new byte[256 * 1024 + 37];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) (i * 31);
        Files.write(source.toPath(), expected);
        Files.write(destination.toPath(), new byte[] { 9, 8, 7 });

        HistoryMediaStore.copyFile(source, destination);

        assertArrayEquals(expected, Files.readAllBytes(destination.toPath()));
        assertFalse(new File(destination.getAbsolutePath() + ".partial").exists());
    }

    @Test public void copyFilePublishesEmptySource() throws Exception {
        File dir = Files.createTempDirectory("history-media-empty").toFile();
        File source = new File(dir, "empty.img");
        File destination = new File(dir, "destination.img");
        Files.write(destination.toPath(), new byte[] { 1, 2, 3 });

        HistoryMediaStore.copyFile(source, destination);

        assertTrue(destination.isFile());
        assertEquals(0L, destination.length());
    }

    private static void assertEquals(long expected, long actual) {
        org.junit.Assert.assertEquals(expected, actual);
    }
}
