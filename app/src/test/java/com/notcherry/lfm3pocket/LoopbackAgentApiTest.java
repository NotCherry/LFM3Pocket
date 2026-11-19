package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

public final class LoopbackAgentApiTest {
    private static final String TOKEN = "unit-test-token";

    @Test public void handleResponsesHaveAccurateUtf8ContentLength() {
        assertContentLength(LoopbackAgentApi.handle("GET", "/health", "", prompt -> true), "ok");
        assertContentLength(LoopbackAgentApi.handle("POST", "/v1/ask", "{\"prompt\":\"ok\"}", prompt -> true), "Accepted");
        assertContentLength(LoopbackAgentApi.handle("POST", "/v1/ask", "{\"prompt\":\"\"}", prompt -> true), "Missing or invalid prompt");
        assertContentLength(LoopbackAgentApi.handle("POST", "/v1/approve", "{}", prompt -> true),
                "Loopback cannot approve Android actions.");
        assertContentLength(LoopbackAgentApi.handle("GET", "/missing", "", prompt -> true), "Not found");
        assertContentLength(LoopbackAgentApi.handle("POST", "/v1/ask", "{\"prompt\":\"busy\"}", prompt -> false),
                "Runtime refused the turn");
    }

    @Test public void askSubmitsPromptAndApproveIsAlwaysForbidden() {
        AtomicReference<String> seen = new AtomicReference<>();
        LoopbackAgentApi.PromptHandler handler = prompt -> { seen.set(prompt); return true; };
        String ask = LoopbackAgentApi.handle("POST", "/v1/ask", "{\"prompt\":\"tide height\"}", handler);
        assertTrue(ask.startsWith("HTTP/1.1 202"));
        assertEquals("tide height", seen.get());

        String approve = LoopbackAgentApi.handle("POST", "/v1/approve", "{\"proposal_id\":\"anything\"}", handler);
        assertTrue(approve.startsWith("HTTP/1.1 403"));
        assertContentLength(approve, "Loopback cannot approve Android actions.");
        assertFalse(approve.contains("202"));
    }

    @Test public void jsonPromptUsesRealJsonEscapingAndPreservesUnicode() {
        AtomicReference<String> seen = new AtomicReference<>();
        String json = "{\"prompt\":\"line\\nquote: \\\"x\\\" — żółć\"}";
        String response = LoopbackAgentApi.handle("POST", "/v1/ask", json, prompt -> { seen.set(prompt); return true; });
        assertTrue(response.startsWith("HTTP/1.1 202"));
        assertEquals("line\nquote: \"x\" — żółć", seen.get());
    }

    @Test public void malformedJsonBlankAndNonStringPromptAreRejected() {
        assertTrue(LoopbackAgentApi.handle("POST", "/v1/ask", "{\"prompt\":", prompt -> true).startsWith("HTTP/1.1 400"));
        assertTrue(LoopbackAgentApi.handle("POST", "/v1/ask", "{\"prompt\":42}", prompt -> true).startsWith("HTTP/1.1 400"));
        assertTrue(LoopbackAgentApi.handle("POST", "/v1/ask", "{\"prompt\":null}", prompt -> true).startsWith("HTTP/1.1 400"));
        assertTrue(LoopbackAgentApi.handle("POST", "/v1/ask", "{\"prompt\":\"\"}", prompt -> true).startsWith("HTTP/1.1 400"));
        assertTrue(LoopbackAgentApi.handle("POST", "/v1/ask", "   ", prompt -> true).startsWith("HTTP/1.1 400"));
    }

    @Test public void readRequestRejectsMalformedHeadersAndRequests() throws Exception {
        assertNull(LoopbackAgentApi.readRequest(stream("\r\n\r\n"), TOKEN));
        assertNull(LoopbackAgentApi.readRequest(stream("GET /health\r\n\r\n"), TOKEN)); // missing HTTP version
        assertNull(LoopbackAgentApi.readRequest(stream("GET /health HTTP/1.1\r\nBadHeader\r\n\r\n"), TOKEN));
        assertNull(LoopbackAgentApi.readRequest(stream("GET /health HTTP/1.1\r\nX: y\r\n folded\r\n\r\n"), TOKEN));
        assertNull(LoopbackAgentApi.readRequest(stream("GET http://evil/ HTTP/1.1\r\n\r\n"), TOKEN));
        assertNotNull(LoopbackAgentApi.readRequest(stream("GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + TOKEN + "\r\n\r\n"), TOKEN));
    }

    @Test public void readRequestParsesValidGetWithAuthorization() throws Exception {
        LoopbackAgentApi.Request req = LoopbackAgentApi.readRequest(stream(
                "GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + TOKEN + "\r\n\r\n"), TOKEN);
        assertNotNull(req);
        assertEquals("GET", req.method);
        assertEquals("/health", req.path);
        assertEquals("", req.body);
        assertTrue(req.authorized);
    }

    @Test public void readRequestReadsUtf8BodyByByteContentLength() throws Exception {
        String prompt = "zażółć gęślą jaźń";
        byte[] body = ("{\"prompt\":\"" + prompt + "\"}").getBytes(StandardCharsets.UTF_8);
        String head = "POST /v1/ask HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + TOKEN
                + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n";
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.write(head.getBytes(StandardCharsets.ISO_8859_1));
        raw.write(body);
        LoopbackAgentApi.Request req = LoopbackAgentApi.readRequest(new ByteArrayInputStream(raw.toByteArray()), TOKEN);
        assertNotNull(req);
        assertTrue(req.authorized);
        assertEquals(body.length, req.body.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(prompt, extractPromptForTest(req.body));
    }

    @Test public void readRequestRejectsInvalidOrOversizedLengthsAndMalformedFraming() throws Exception {
        assertNull(LoopbackAgentApi.readRequest(stream("POST /v1/ask HTTP/1.1\r\nContent-Length: -1\r\n\r\n"), TOKEN));
        assertNull(LoopbackAgentApi.readRequest(stream("POST /v1/ask HTTP/1.1\r\nContent-Length: nope\r\n\r\n"), TOKEN));
        assertNull(LoopbackAgentApi.readRequest(stream("POST /v1/ask HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\nx"), TOKEN));
        assertNull(LoopbackAgentApi.readRequest(stream("POST /v1/ask HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"), TOKEN));
        assertNull(LoopbackAgentApi.readRequest(stream("POST /v1/ask HTTP/1.1\r\nX-Test:\r\n folded\r\n\r\n"), TOKEN));
        assertNull(LoopbackAgentApi.readRequest(stream("POST /v1/ask HTTP/1.1\r\nBadHeader\r\n\r\n"), TOKEN));
        assertNull(LoopbackAgentApi.readRequest(stream("POST /v1/ask HTTP/1.1\r\nContent-Length: " + (LoopbackAgentApi.MAX_BODY_BYTES + 1) + "\r\n\r\n"), TOKEN));
        assertNull(LoopbackAgentApi.readRequest(stream("POST /v1/ask HTTP/1.1\r\nContent-Length: 5\r\n\r\nabc"), TOKEN)); // truncated body
        byte[] hugeHeader = ("GET /health HTTP/1.1\r\nX-Pad: " + "a".repeat(LoopbackAgentApi.MAX_HEADER_BYTES) + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        assertNull(LoopbackAgentApi.readRequest(new ByteArrayInputStream(hugeHeader), TOKEN)); // oversized header/request framing
    }

    @Test public void readRequestMarksUnauthorizedCaller() throws Exception {
        LoopbackAgentApi.Request missing = LoopbackAgentApi.readRequest(stream("GET /health HTTP/1.1\r\nHost: x\r\n\r\n"), TOKEN);
        assertNotNull(missing);
        assertFalse(missing.authorized);
        LoopbackAgentApi.Request wrong = LoopbackAgentApi.readRequest(stream(
                "GET /health HTTP/1.1\r\nAuthorization: Bearer wrong\r\n\r\n"), TOKEN);
        assertNotNull(wrong);
        assertFalse(wrong.authorized);
    }

    @Test public void realListenerRequiresAuthorizationAndServesAuthorizedRequests() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        LoopbackAgentApi api = new LoopbackAgentApi(prompt -> { seen.set(prompt); return true; }, msg -> {}, () -> TOKEN);
        api.setEnabled(true);
        try {
            awaitPort(api);
            String unauthorized = sendRaw(api.port(),
                    "POST /v1/ask HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: 17\r\n\r\n{\"prompt\":\"hello\"}",
                    2000);
            assertTrue(unauthorized, unauthorized.startsWith("HTTP/1.1 401"));
            assertContentLength(unauthorized, "Unauthorized");
            assertNull(seen.get());

            String prompt = "zażółć gęślą";
            byte[] body = ("{\"prompt\":\"" + prompt + "\"}").getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            raw.write(("POST /v1/ask HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + TOKEN
                    + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            raw.write(body);
            String ok = sendRaw(api.port(), raw.toByteArray(), 2000);
            assertTrue(ok, ok.startsWith("HTTP/1.1 202"));
            assertEquals(prompt, seen.get());
        } finally {
            api.setEnabled(false);
            api.stop();
        }
    }

    @Test public void realListenerRejectsOversizedBodyAndTimesOutIncompleteClient() throws Exception {
        LoopbackAgentApi api = new LoopbackAgentApi(prompt -> true, msg -> {}, () -> TOKEN);
        api.setEnabled(true);
        try {
            awaitPort(api);
            String big = "POST /v1/ask HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + TOKEN
                    + "\r\nContent-Length: " + (LoopbackAgentApi.MAX_BODY_BYTES + 1) + "\r\n\r\n";
            String oversized = sendRaw(api.port(), big, 2000);
            assertTrue(oversized, oversized.startsWith("HTTP/1.1 400"));
            assertContentLength(oversized, "Malformed or oversized request");

            long start = System.currentTimeMillis();
            try (Socket socket = new Socket("127.0.0.1", api.port())) {
                socket.getOutputStream().write("POST /v1/ask HTTP/1.1\r\nHost: 127.0.0.1\r\n".getBytes(StandardCharsets.ISO_8859_1));
                socket.getOutputStream().flush();
                int r = socket.getInputStream().read();
                assertEquals(-1, r); // server times out/closes incomplete request
            }
            assertTrue("expected server read timeout to close stalled client", System.currentTimeMillis() - start < 7000);
        } finally {
            api.setEnabled(false);
            api.stop();
        }
    }

    private static void awaitPort(LoopbackAgentApi api) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (api.isRunning() && api.port() > 0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Loopback API did not start");
    }

    private static String sendRaw(int port, String request, int timeoutMs) throws Exception {
        return sendRaw(port, request.getBytes(StandardCharsets.ISO_8859_1), timeoutMs);
    }

    private static String sendRaw(int port, byte[] request, int timeoutMs) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            out.write(request);
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            try {
                while ((n = in.read(buf)) >= 0) response.write(buf, 0, n);
            } catch (java.io.IOException ignored) {
                // Tolerate reset/close races after response bytes are written.
            }
            return new String(response.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void assertContentLength(String response, String expectedBody) {
        assertNotNull(response);
        byte[] bodyBytes = expectedBody.getBytes(StandardCharsets.UTF_8);
        String lower = response.toLowerCase(java.util.Locale.ROOT);
        String marker = "\r\ncontent-length: ";
        int idx = lower.indexOf(marker);
        assertTrue("missing content-length in: " + response, idx >= 0);
        int end = lower.indexOf("\r\n", idx + marker.length());
        int len = Integer.parseInt(response.substring(idx + marker.length(), end).trim());
        assertEquals("content-length for body '" + expectedBody + "'", bodyBytes.length, len);
        assertTrue(response.endsWith("\r\n\r\n" + expectedBody));
    }

    private static String extractPromptForTest(String jsonBody) {
        try {
            return new org.json.JSONObject(jsonBody).getString("prompt");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
