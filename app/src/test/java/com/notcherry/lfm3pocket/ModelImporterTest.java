package com.notcherry.lfm3pocket;

import org.junit.Test;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class ModelImporterTest {
    private byte[] fixture() {
        byte[] bytes = new byte[1024 * 1024];
        bytes[0]='G'; bytes[1]='G'; bytes[2]='U'; bytes[3]='F'; bytes[4]=3;
        return bytes;
    }
    private void assertEmpty(File dir) { assertEquals(0, dir.list().length); }
    @Test public void publishesCompletePrivateFileAndReportsProgress() throws Exception {
        File dir = Files.createTempDirectory("import").toFile();
        long[] progress = {0};
        File file = new ModelImporter(dir).importFile(() -> new ByteArrayInputStream(fixture()),
                fixture().length, new AtomicBoolean(), bytes -> progress[0] = bytes);
        assertEquals(fixture().length, file.length());
        assertEquals(file.length(), progress[0]);
        assertTrue(file.getName().endsWith(".gguf"));
        assertEquals(1, dir.list().length);
    }
    @Test public void deniedCorruptAndTruncatedSourcesLeaveNoPartialSelection() throws Exception {
        File dir = Files.createTempDirectory("import").toFile();
        ModelImporter importer = new ModelImporter(dir);
        for (int mode=0; mode<3; mode++) {
            final int selected = mode;
            try {
                importer.importFile(() -> {
                    if (selected == 0) throw new SecurityException("Permission denied");
                    byte[] bytes = fixture();
                    if (selected == 1) bytes[0]=0;
                    return new ByteArrayInputStream(bytes);
                }, mode == 2 ? fixture().length + 1 : -1, new AtomicBoolean(), bytes -> {});
                fail("Invalid source accepted");
            } catch (IOException | SecurityException expected) { assertEmpty(dir); }
        }
    }
    @Test public void cancellationCleansTemporaryFileAndPreservesExistingModel() throws Exception {
        File dir = Files.createTempDirectory("import").toFile();
        ModelImporter importer = new ModelImporter(dir);
        File previous = importer.importFile(() -> new ByteArrayInputStream(fixture()), -1,
                new AtomicBoolean(), bytes -> {});
        AtomicBoolean cancel = new AtomicBoolean();
        try {
            importer.importFile(() -> new ByteArrayInputStream(fixture()), -1, cancel,
                    bytes -> cancel.set(true));
            fail("Cancelled import published");
        } catch (InterruptedIOException expected) {
            assertTrue(previous.isFile()); assertEquals(1, dir.list().length);
        }
    }
    @Test public void streamFailureAndShortHeaderLeaveNoFiles() throws Exception {
        File dir = Files.createTempDirectory("import").toFile();
        ModelImporter importer = new ModelImporter(dir);
        try {
            importer.importFile(() -> new InputStream() {
                @Override public int read() throws IOException { throw new IOException("read failed"); }
            }, -1, new AtomicBoolean(), bytes -> {});
            fail();
        } catch (IOException expected) { assertEmpty(dir); }
        try {
            importer.importFile(() -> new ByteArrayInputStream(new byte[]{'G','G','U','F'}),
                    -1, new AtomicBoolean(), bytes -> {});
            fail();
        } catch (IOException expected) { assertEmpty(dir); }
    }
}
