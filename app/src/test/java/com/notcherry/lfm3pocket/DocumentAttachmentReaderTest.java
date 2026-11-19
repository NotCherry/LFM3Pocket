package com.notcherry.lfm3pocket;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class DocumentAttachmentReaderTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void readsPlainTextFile() throws Exception {
        File file = tmp.newFile("notes.txt");
        Files.write(file.toPath(), "Hello from a document.".getBytes(StandardCharsets.UTF_8));
        DocumentAttachmentReader.Result result = DocumentAttachmentReader.read(file, "notes.txt", "text/plain");
        assertTrue(result.error, result.isOk());
        assertEquals("notes.txt", result.displayName);
        assertEquals("Hello from a document.", result.text);
    }

    @Test public void rejectsUnsupportedFileType() throws Exception {
        File file = tmp.newFile("image.bin");
        Files.write(file.toPath(), new byte[]{0, 1, 2, 3, 4, 5, 6});
        DocumentAttachmentReader.Result result = DocumentAttachmentReader.read(file, "image.bin", "application/octet-stream");
        assertFalse(result.isOk());
        assertTrue(result.error, result.error.toLowerCase().contains("unsupported"));
    }

    @Test public void rejectsTooLargeTextFile() throws Exception {
        File file = tmp.newFile("big.txt");
        byte[] data = new byte[(int) DocumentAttachmentReader.MAX_TEXT_BYTES + 1];
        for (int i = 0; i < data.length; i++) data[i] = (byte) 'a';
        Files.write(file.toPath(), data);
        DocumentAttachmentReader.Result result = DocumentAttachmentReader.read(file, "big.txt", "text/plain");
        assertFalse(result.isOk());
        assertTrue(result.error, result.error.toLowerCase().contains("too large"));
    }

    @Test public void pdfIsReportedAsUnsupportedWithClearError() throws Exception {
        File file = tmp.newFile("paper.pdf");
        Files.write(file.toPath(), "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8));
        DocumentAttachmentReader.Result result = DocumentAttachmentReader.read(file, "paper.pdf", "application/pdf");
        assertFalse(result.isOk());
        assertTrue(result.error, result.error.toLowerCase().contains("pdf"));
        assertTrue(result.error, result.error.toLowerCase().contains("not supported") || result.error.toLowerCase().contains("attach a text"));
    }

    @Test public void rejectsEmptyTextFile() throws Exception {
        File file = tmp.newFile("empty.txt");
        Files.write(file.toPath(), " \n\t".getBytes(StandardCharsets.UTF_8));
        DocumentAttachmentReader.Result result = DocumentAttachmentReader.read(file, "empty.txt", "text/plain");
        assertFalse(result.isOk());
        assertNotNull(result.error);
    }
}
