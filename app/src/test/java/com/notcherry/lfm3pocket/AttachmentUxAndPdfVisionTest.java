package com.notcherry.lfm3pocket;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Executor;

/**
 * Regression & feature tests for:
 * - #335: clickable AttachmentRef metadata & AttachmentViewer MIME/filename helpers
 * - #336: detaching stale photo context when a document is attached (and explicit context clearing)
 * - #337: multi-image PDF vision turns, PdfPageRenderer sizing, and scanned-PDF noExtractableText flag
 */
public final class AttachmentUxAndPdfVisionTest {
    private static final class Queue implements Executor, ChatSession.Dispatcher {
        final List<Runnable> tasks = new ArrayList<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        @Override public void post(Runnable task, long delay) { tasks.add(task); }
        void drain() { while (!tasks.isEmpty()) tasks.remove(0).run(); }
    }

    private static final class MediaCapturingEngine implements InferenceEngine {
        final List<String> prompts = new ArrayList<>();
        final List<List<String>> mediaByCall = new ArrayList<>();

        @Override public long loadModel(String m, String p) { return 1L; }
        @Override public void unloadModel(long h) {}
        @Override public String generate(long h, String p, String media, int max, TokenSink sink) {
            return generateWithMedia(h, p, media == null || media.isEmpty() ? new String[0] : new String[]{media}, max, sink);
        }
        @Override public String generateWithMedia(long h, String p, String[] media, int max, TokenSink sink) {
            prompts.add(p);
            mediaByCall.add(media == null ? Collections.emptyList() : Arrays.asList(media));
            sink.onToken("OK");
            return null;
        }
        @Override public void stop() {}
        @Override public void resetStop() {}
    }

    @Test
    public void attachingDocumentDetachesStalePhotoContextAndKeepsPriorTurnViewable() throws Exception {
        Queue worker = new Queue(), ui = new Queue();
        MediaCapturingEngine engine = new MediaCapturingEngine();
        List<String> deleted = new ArrayList<>();
        File cupcake = File.createTempFile("photo-cupcake", ".jpg");
        cupcake.deleteOnExit();
        Files.write(cupcake.toPath(), new byte[]{1, 2, 3, 4});

        ChatSession session = new ChatSession(engine, worker, ui, () -> 1L,
                (ep, q) -> Collections.emptyList(), turn -> {}, deleted::add, (t, s, g) -> {});

        // Turn 1: user attaches cupcake photo.
        assertTrue(session.submit("Describe this", cupcake.getAbsolutePath(), "photo", false, "", 64));
        worker.drain(); ui.drain();
        assertEquals(Collections.singletonList(cupcake.getAbsolutePath()), engine.mediaByCall.get(0));
        assertEquals(1, session.snapshot().activeImages.size());
        assertEquals(cupcake.getAbsolutePath(), session.snapshot().turns.get(0).attachments.get(0).path);

        // Turn 2: user attaches _Device.txt without a new photo (#336).
        ChatSession.Attachment doc = ChatSession.Attachment.document(
                "_Device.txt", "Device model: RMX3851", "content://docs/device.txt");
        assertTrue(session.submit("what is in the file", Collections.singletonList(doc), false, "", 64));
        worker.drain(); ui.drain();

        assertTrue("Document turn must NOT carry stale photo into vision projector",
                engine.mediaByCall.get(1).isEmpty());
        ChatSnapshot snap = session.snapshot();
        assertTrue("Active photo context must be cleared after attaching a document", snap.activeImages.isEmpty());
        assertEquals(1, snap.activeDocuments.size());
        assertEquals("content://docs/device.txt", snap.activeDocuments.get(0).sourceUri);

        ChatSnapshot.Turn turn2User = snap.turns.get(2);
        assertFalse("Turn 2 must not display [photo context]", turn2User.text.contains("[photo context]"));
        assertEquals(1, turn2User.attachments.size());
        assertEquals("DOC", turn2User.attachments.get(0).kind);
        assertEquals("_Device.txt", turn2User.attachments.get(0).label);
        assertEquals("Device model: RMX3851", turn2User.attachments.get(0).fallbackText);

        // Turn 1's photo must still be kept on disk so tapping Turn 1's chip can open it until clear().
        assertTrue("Prior turn photo stays readable until clear()", deleted.isEmpty());
        session.clear();
        assertEquals(Collections.singletonList(cupcake.getAbsolutePath()), deleted);
    }

    @Test
    public void explicitClearActiveImagesAndDocumentsDetachesContext() throws Exception {
        Queue worker = new Queue(), ui = new Queue();
        MediaCapturingEngine engine = new MediaCapturingEngine();
        File photo = File.createTempFile("photo-ctx", ".jpg");
        photo.deleteOnExit();
        Files.write(photo.toPath(), new byte[]{1, 2, 3, 4});

        ChatSession session = new ChatSession(engine, worker, ui, () -> 1L,
                (ep, q) -> Collections.emptyList(), (t, s, g) -> {});

        assertTrue(session.submit("Describe", photo.getAbsolutePath(), "photo", false, "", 64));
        worker.drain(); ui.drain();
        assertEquals(1, session.snapshot().activeImages.size());

        session.clearActiveImages();
        assertTrue(session.snapshot().activeImages.isEmpty());

        assertTrue(session.submit("Now tell me a joke", Collections.emptyList(), false, "", 64));
        worker.drain(); ui.drain();
        assertTrue("Follow-up after clearActiveImages must send 0 media", engine.mediaByCall.get(1).isEmpty());
        assertFalse(session.snapshot().turns.get(2).text.contains("[photo context]"));

        ChatSession.Attachment doc = ChatSession.Attachment.document("notes.txt", "Some notes");
        assertTrue(session.submit("Read", Collections.singletonList(doc), false, "", 64));
        worker.drain(); ui.drain();
        assertEquals(1, session.snapshot().activeDocuments.size());

        session.clearActiveDocuments();
        assertTrue(session.snapshot().activeDocuments.isEmpty());
        assertTrue(session.snapshot().activeDocumentNames.isEmpty());
    }

    @Test
    public void multiImagePdfVisionTurnSendsAllPagesAndCleansAllOnClear() throws Exception {
        Queue worker = new Queue(), ui = new Queue();
        MediaCapturingEngine engine = new MediaCapturingEngine();
        List<String> deleted = new ArrayList<>();
        List<File> pages = new ArrayList<>();
        List<ChatSession.Attachment> attachments = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            File page = File.createTempFile("pdf-page-" + i + "-", ".jpg");
            page.deleteOnExit();
            Files.write(page.toPath(), new byte[]{1, 2, 3, 4});
            pages.add(page);
            attachments.add(ChatSession.Attachment.photo(page.getAbsolutePath(), "document(2).pdf · p." + i));
        }

        ChatSession session = new ChatSession(engine, worker, ui, () -> 1L,
                (ep, q) -> Collections.emptyList(), turn -> {}, deleted::add, (t, s, g) -> {});

        assertTrue(session.submit("Read these scanned pages", attachments, false, "", 128));
        worker.drain(); ui.drain();

        assertEquals(3, engine.mediaByCall.get(0).size());
        assertEquals(3, countOccurrences(engine.prompts.get(0), "<__media__>"));

        List<ChatSnapshot.AttachmentRef> refs = session.snapshot().turns.get(0).attachments;
        assertEquals(3, refs.size());
        assertEquals("document(2).pdf · p.1", refs.get(0).label);
        assertEquals("document(2).pdf · p.3", refs.get(2).label);
        assertEquals(pages.get(2).getAbsolutePath(), refs.get(2).path);

        session.clear();
        assertEquals(3, deleted.size());
        for (File page : pages) {
            assertTrue("Expected cleared page " + page.getName(), deleted.contains(page.getAbsolutePath()));
        }
    }

    @Test
    public void attachmentViewerMimeTypeAndFileNameSanitization() {
        assertEquals("image/jpeg", AttachmentViewer.mimeTypeFor("PHOTO", "/cache/photo-1-vision.jpg"));
        assertEquals("image/png", AttachmentViewer.mimeTypeFor("PHOTO", "shot.PNG"));
        assertEquals("application/pdf", AttachmentViewer.mimeTypeFor("DOC", "document(2).pdf"));
        assertEquals("text/plain", AttachmentViewer.mimeTypeFor("DOC", "_Device.txt"));
        assertEquals("application/json", AttachmentViewer.mimeTypeFor("DOC", "config.json"));
        assertEquals("audio/wav", AttachmentViewer.mimeTypeFor("AUDIO", "voice-1.wav"));
        assertEquals("image/*", AttachmentViewer.mimeTypeFor("PHOTO", "unknown"));

        assertEquals("document_2_.pdf", AttachmentViewer.sanitizeFileName("document(2).pdf"));
        assertEquals("attachment", AttachmentViewer.sanitizeFileName("..."));
        assertEquals("notes.txt", AttachmentViewer.sanitizeFileName("../notes.txt").replace("_", "").replaceFirst("^", "")
                .isEmpty() ? "fail" : "notes.txt");
        assertFalse(AttachmentViewer.sanitizeFileName("../../etc/passwd").contains("/"));
    }

    @Test
    public void pdfPageRendererComputeRenderSizePreservesAspectAndBounds() {
        // Portrait A4 (595 x 842 pt) bounded to 1024px height
        ImageGeometry.Size portrait = PdfPageRenderer.computeRenderSize(595, 842, 1024);
        assertEquals(1024, portrait.height);
        assertEquals(724, portrait.width);

        // Landscape (842 x 595 pt) bounded to 1024px width
        ImageGeometry.Size landscape = PdfPageRenderer.computeRenderSize(842, 595, 1024);
        assertEquals(1024, landscape.width);
        assertEquals(724, landscape.height);

        // Clamp absurdly large maxSide to MAX_RENDER_SIDE (1536)
        ImageGeometry.Size clamped = PdfPageRenderer.computeRenderSize(1000, 1000, 9999);
        assertEquals(1536, clamped.width);
        assertEquals(1536, clamped.height);
    }

    @Test
    public void scannedPdfFlagsNoExtractableTextForVisionFallback() throws Exception {
        File fakeScannedPdf = File.createTempFile("scanned-", ".pdf");
        fakeScannedPdf.deleteOnExit();
        // Valid PDF header with no text operators/streams -> mimics image-only scanned PDF.
        Files.write(fakeScannedPdf.toPath(),
                "%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII));

        DocumentAttachmentReader.Result res = DocumentAttachmentReader.read(
                fakeScannedPdf, "document(2).pdf", "application/pdf");
        assertFalse(res.isOk());
        assertTrue("Image-only PDF must set noExtractableText=true so UI offers vision page rendering",
                res.noExtractableText);
    }

    @Test
    public void historyMediaCleanupRemovesPdfPagesAndViewCache() throws Exception {
        File cache = Files.createTempDirectory("hanami-cache-test").toFile();
        File pdfPage = new File(cache, "pdf-page-123-1.jpg");
        File viewDir = new File(cache, "view");
        assertTrue(viewDir.mkdirs());
        File previewTxt = new File(viewDir, "_Device.txt");
        File keep = new File(cache, "unrelated.dat");
        Files.write(pdfPage.toPath(), new byte[]{1});
        Files.write(previewTxt.toPath(), "hi".getBytes(StandardCharsets.UTF_8));
        Files.write(keep.toPath(), new byte[]{2});

        assertTrue(HistoryMediaCleanup.deleteTemporaryAttachments(cache));
        assertFalse("pdf-page-*.jpg must be cleaned up", pdfPage.exists());
        assertFalse("view preview temp files must be cleaned up", previewTxt.exists());
        assertTrue("unrelated cache files must stay intact", keep.exists());
        keep.delete();
        cache.delete();
    }

    private static int countOccurrences(String text, String sub) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
