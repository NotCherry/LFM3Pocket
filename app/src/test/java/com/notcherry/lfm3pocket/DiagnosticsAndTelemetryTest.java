package com.notcherry.lfm3pocket;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class DiagnosticsAndTelemetryTest {

    @Test
    public void processingTelemetryCapsAt60EntriesAndFormatsLines() {
        ProcessingTelemetry telemetry = new ProcessingTelemetry();
        for (int i = 0; i < 80; i++) {
            telemetry.record("PREFILL", i * 10L, "chunk " + i);
        }
        assertEquals(60, telemetry.snapshot().size());
        List<String> recent = telemetry.recentLines(3);
        assertEquals(3, recent.size());
        assertTrue(recent.get(2).contains("[PREFILL]"));
        assertTrue(recent.get(2).contains("chunk 79"));
        telemetry.clear();
        assertTrue(telemetry.snapshot().isEmpty());
    }

    @Test
    public void diagnosticsReportSanitizesSecretsAndPaths() {
        DeviceResourceMonitor.Snapshot resources = new DeviceResourceMonitor.Snapshot(8192, 2048, 1150, false, "light");
        assertTrue(resources.compactBadge().contains("RAM 2.0 GB free"));
        assertTrue(resources.compactBadge().contains("app 1150 MB"));

        DiagnosticsReportBuilder.DeviceInfo info = new DiagnosticsReportBuilder.DeviceInfo(
                "1.4.0",
                "Pixel 8",
                34,
                "arm64-v8a",
                "Hanami native | arm64",
                "/data/user/0/dev.notcherry.hanami/files/models/LFM3-2B.gguf",
                true,
                "ctx=4096, threads=4, maxTokens=512",
                resources);

        String report = DiagnosticsReportBuilder.buildMarkdown(
                info,
                Arrays.asList(
                        "12:00:01 [PDF_IMPORT] 420ms · ok report.pdf (4.1 MB) chars=12000",
                        "12:00:02 [AUTH] header=Bearer superSecretTokenValue12345"),
                "{\"kind\":\"MODEL_LOAD\",\"detail\":\"token=myPrivateToken999\"}",
                "Fatal: api_key=abc123456789xyz");

        assertTrue(report.contains("LFM3-2B.gguf"));
        assertFalse(report.contains("/data/user/0/"));
        assertFalse(report.contains("superSecretTokenValue12345"));
        assertFalse(report.contains("myPrivateToken999"));
        assertFalse(report.contains("abc123456789xyz"));
        assertTrue(report.contains("[REDACTED]"));

        String issueUrl = DiagnosticsReportBuilder.buildGitHubIssueUrl(
                "PDF prefill slow", report, "https://dpaste.org/AbCdE");
        assertTrue(issueUrl.startsWith(DiagnosticsReportBuilder.GITHUB_NEW_ISSUE_BASE + "?title="));
        assertTrue(issueUrl.contains("https%3A%2F%2Fdpaste.org%2FAbCdE"));
    }

    @Test
    public void pasteUploaderUsesDpasteAndFallsBackToPasteRsWithRedaction() throws Exception {
        AtomicReference<String> seenBody = new AtomicReference<>();
        String direct = DiagnosticsPasteUploader.upload(
                "log line Bearer secret_token_123456789",
                (endpoint, contentType, body) -> {
                    seenBody.set(new String(body, StandardCharsets.UTF_8));
                    assertEquals(DiagnosticsPasteUploader.DPASTE_ENDPOINT, endpoint);
                    return "\"https://dpaste.org/XYZ123\"\n";
                });
        assertEquals("https://dpaste.org/XYZ123", direct);
        assertFalse(seenBody.get().contains("secret_token_123456789"));

        AtomicInteger calls = new AtomicInteger();
        String fallback = DiagnosticsPasteUploader.upload(
                "diagnostics body",
                (endpoint, contentType, body) -> {
                    int n = calls.incrementAndGet();
                    if (n == 1) {
                        assertEquals(DiagnosticsPasteUploader.DPASTE_ENDPOINT, endpoint);
                        throw new IOException("503 unavailable");
                    }
                    assertEquals(DiagnosticsPasteUploader.PASTE_RS_ENDPOINT, endpoint);
                    return "https://paste.rs/abcde\n";
                });
        assertEquals("https://paste.rs/abcde", fallback);
        assertEquals(2, calls.get());

        try {
            DiagnosticsPasteUploader.HTTP_TRANSPORT.post("http://insecure.example/api", "text/plain", new byte[]{1});
            fail("Expected non-HTTPS endpoint to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("HTTPS"));
        }
    }

    @Test
    public void lanDiagnosticsServerEnforcesTokenReadOnlyAndRedaction() throws Exception {
        LanDiagnosticsServer server = new LanDiagnosticsServer(
                () -> "# Report\nBearer leaked_lan_secret_987654321",
                () -> "{\"ok\":true,\"token\":\"secret_ndjson_token_999\"}\n",
                0);
        try {
            String url = server.start();
            assertTrue(server.isRunning());
            assertTrue(url.contains("token=" + server.token()));
            int port = server.port();

            // Missing token -> 403
            HttpURLConnection forbidden = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/").openConnection();
            assertEquals(403, forbidden.getResponseCode());
            forbidden.disconnect();

            // POST -> 405
            HttpURLConnection post = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/?token=" + server.token()).openConnection();
            post.setRequestMethod("POST");
            assertEquals(405, post.getResponseCode());
            post.disconnect();

            // Valid GET /raw -> 200 and redacted
            HttpURLConnection raw = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/raw?token=" + server.token()).openConnection();
            assertEquals(200, raw.getResponseCode());
            String rawBody = readAll(raw);
            raw.disconnect();
            assertTrue(rawBody.contains("# Report"));
            assertTrue(rawBody.contains("[REDACTED]"));
            assertFalse(rawBody.contains("leaked_lan_secret_987654321"));

            // Valid GET /ndjson -> 200 and redacted
            HttpURLConnection ndjson = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/ndjson?token=" + server.token()).openConnection();
            assertEquals(200, ndjson.getResponseCode());
            String ndBody = readAll(ndjson);
            ndjson.disconnect();
            assertTrue(ndBody.contains("\"ok\":true"));
            assertFalse(ndBody.contains("secret_ndjson_token_999"));
        } finally {
            server.stop();
            assertFalse(server.isRunning());
        }
    }

    @Test
    public void chatSessionRecordsStageProgressAndExposesActiveDocsAndTraceInSnapshot() {
        ProcessingTelemetry telemetry = new ProcessingTelemetry();
        List<String> observedStatuses = new ArrayList<>();
        AtomicReference<ChatSnapshot> lastSnapshot = new AtomicReference<>();

        InferenceEngine fakeEngine = new InferenceEngine() {
            @Override public long loadModel(String modelPath, String projectorPath) { return 1L; }
            @Override public void unloadModel(long handle) {}
            @Override public String generate(long handle, String prompt, String mediaPath, int maxTokens, TokenSink sink) {
                sink.onProgress("tokenize", 1024, 4096);
                sink.onProgress("prefill", 512, 1024);
                sink.onProgress("prefill", 1024, 1024);
                sink.onProgress("decode", 0, maxTokens);
                sink.onToken("Summary ready.");
                return null;
            }
            @Override public void stop() {}
            @Override public void resetStop() {}
        };

        ChatSession session = new ChatSession(
                fakeEngine,
                Runnable::run,
                (work, delay) -> work.run(),
                () -> 1L,
                (endpoint, q) -> Collections.emptyList(),
                turn -> {},
                path -> {},
                (transcript, status, generating) -> observedStatuses.add(status),
                lastSnapshot::set);
        session.setTelemetry(telemetry, () -> "RAM 2.4 GB free");

        ChatSession.Attachment doc = ChatSession.Attachment.document("quarterly_report.pdf", "Revenue grew 22% year over year.");
        assertTrue(session.submit("Summarize this PDF", Collections.singletonList(doc), false, "", 256));

        assertTrue(observedStatuses.stream().anyMatch(s -> s.contains("Prefilling prompt · 512/1024 tok (50%) · RAM 2.4 GB free")));
        ChatSnapshot snap = lastSnapshot.get();
        assertNotNull(snap);
        assertEquals(1, snap.activeDocumentNames.size());
        assertTrue(snap.activeDocumentNames.get(0).contains("quarterly_report.pdf"));
        assertFalse(snap.traceLines.isEmpty());
        assertTrue(snap.traceLines.stream().anyMatch(line -> line.contains("[PREFILL]")));
    }

    @Test
    public void parseUserTurnAttachmentsAndDocChipFormatting() {
        ParsedUserTurn parsed = ChatTurnListKt.parseUserTurnAttachments(
                "Please summarize these files\n[photo attached]\n[documents attached: spec.pdf, notes.md]");
        assertEquals("Please summarize these files", parsed.getBody());
        assertEquals(3, parsed.getAttachments().size());
        assertEquals("PHOTO", parsed.getAttachments().get(0).getKind());
        assertEquals("DOC", parsed.getAttachments().get(1).getKind());
        assertEquals("spec.pdf", parsed.getAttachments().get(1).getLabel());
        assertEquals("notes.md", parsed.getAttachments().get(2).getLabel());

        assertEquals("4.0 MB", AttachmentImportCoordinator.formatByteSize(4L * 1024L * 1024L));
        ChatSession.Attachment doc = ChatSession.Attachment.document("big.pdf", "a".repeat(4000));
        String chip = AttachmentImportCoordinator.formatDocChipLabel(doc);
        assertTrue(chip.contains("big.pdf"));
        assertTrue(chip.contains("4.0k chars"));
        assertTrue(chip.contains("~1.0k tok"));
    }

    private static String readAll(HttpURLConnection conn) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }
}
