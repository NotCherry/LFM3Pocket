package com.notcherry.lfm3pocket;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.Deflater;

/** Unit tests for pure-Java offline PdfTextExtractor and DocumentAttachmentReader PDF support. */
public final class PdfTextExtractorTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static byte[] deflate(String text) {
        byte[] input = text.getBytes(StandardCharsets.ISO_8859_1);
        Deflater deflater = new Deflater();
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            out.write(buf, 0, n);
        }
        deflater.end();
        return out.toByteArray();
    }

    @Test public void extractsUncompressedPdfTextAndEscapedParens() throws Exception {
        String pdf = "%PDF-1.4\n"
                + "1 0 obj\n<< /Length 74 >>\nstream\n"
                + "BT /F1 12 Tf 72 720 Td (Hello \\(Offline\\) PDF!) Tj T* [(Line ) -200 (Two)] TJ ET\n"
                + "endstream\nendobj\n%%EOF";
        File file = tmp.newFile("sample.pdf");
        Files.write(file.toPath(), pdf.getBytes(StandardCharsets.ISO_8859_1));

        DocumentAttachmentReader.Result res = DocumentAttachmentReader.read(file, "sample.pdf", "application/pdf");
        assertTrue(res.error, res.isOk());
        assertTrue(res.text, res.text.contains("Hello (Offline) PDF!"));
        assertTrue(res.text, res.text.contains("Line Two"));
    }

    @Test public void extractsFlateDecodeCompressedPdfStream() throws Exception {
        byte[] compressed = deflate("BT (Compressed Hanami PDF) Tj ET");
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        pdf.write(("%PDF-1.5\n1 0 obj\n<< /Filter /FlateDecode /Length " + compressed.length + " >>\nstream\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        pdf.write(compressed);
        pdf.write("\nendstream\nendobj\n%%EOF".getBytes(StandardCharsets.ISO_8859_1));

        String extracted = PdfTextExtractor.extract(pdf.toByteArray(), 4000);
        assertEquals("Compressed Hanami PDF", extracted);
    }

    @Test public void decodesToUnicodeCMapHexStrings() {
        String pdf = "%PDF-1.4\n"
                + "1 0 obj\n<< /Length 80 >>\nstream\n"
                + "begincmap\n2 beginbfchar\n<0001> <0048>\n<0002> <0069>\nendbfchar\nendcmap\n"
                + "endstream\nendobj\n"
                + "2 0 obj\n<< /Length 28 >>\nstream\n"
                + "BT <00010002> Tj ET\n"
                + "endstream\nendobj\n%%EOF";
        String extracted = PdfTextExtractor.extract(pdf.getBytes(StandardCharsets.ISO_8859_1), 1000);
        assertEquals("Hi", extracted);
    }

    @Test public void returnsEmptyForEncryptedPdf() {
        String pdf = "%PDF-1.4\n1 0 obj\n<< /Encrypt 2 0 R >>\nendobj\n"
                + "3 0 obj\n<< /Length 20 >>\nstream\nBT (Secret) Tj ET\nendstream\nendobj\n%%EOF";
        assertEquals("", PdfTextExtractor.extract(pdf.getBytes(StandardCharsets.ISO_8859_1), 1000));
    }
}
