package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class LoopbackAgentApiInstrumentationTest {
    private static final String TOKEN = "instrumentation-test-token";

    @Test
    public void realListenerRequiresAuthorizationAndRejectsUnauthorizedCallers() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        LoopbackAgentApi api = new LoopbackAgentApi(prompt -> { seen.set(prompt); return true; }, msg -> {}, () -> TOKEN, 0);
        api.setEnabled(true);
        try {
            awaitPort(api);

            String missing = sendRaw(api.port(),
                    "POST /v1/ask HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: 17\r\n\r\n{\"prompt\":\"hello\"}",
                    3000);
            assertStartsWith(missing, "HTTP/1.1 401");
            assertContentLength(missing, "Unauthorized");
            assertNull(seen.get());

            String wrong = sendRaw(api.port(),
                    "POST /v1/ask HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer wrong\r\nContent-Type: application/json\r\nContent-Length: 17\r\n\r\n{\"prompt\":\"hello\"}",
                    3000);
            assertStartsWith(wrong, "HTTP/1.1 401");
            assertContentLength(wrong, "Unauthorized");
            assertNull(seen.get());

            String health = sendRaw(api.port(),
                    "GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n",
                    3000);
            assertStartsWith(health, "HTTP/1.1 401");
        } finally {
            api.stop();
            api.setEnabled(false);
        }
    }

    @Test
    public void realListenerHandlesUnicodeByteLengthsEscapingAndForbiddenApprove() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        LoopbackAgentApi api = new LoopbackAgentApi(prompt -> { seen.set(prompt); return true; }, msg -> {}, () -> TOKEN, 0);
        api.setEnabled(true);
        try {
            awaitPort(api);

            String prompt = "zażółć\ngęślą \"jaźń\" — ✅";
            String json = new JSONObject().put("prompt", prompt).toString();
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            assertTrue("test requires multibyte UTF-8 prompt", body.length > prompt.length());
            byte[] request = concat(
                    ("POST /v1/ask HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + TOKEN
                            + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n")
                            .getBytes(StandardCharsets.ISO_8859_1),
                    body);
            String ok = sendRaw(api.port(), request, 3000);
            assertStartsWith(ok, "HTTP/1.1 202");
            assertContentLength(ok, "Accepted");
            assertEquals(prompt, seen.get());

            String approve = sendRaw(api.port(),
                    "POST /v1/approve HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + TOKEN
                            + "\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}",
                    3000);
            assertStartsWith(approve, "HTTP/1.1 403");
            assertContentLength(approve, "Loopback cannot approve Android actions.");
            assertTrue(approve.contains("cannot approve"));
        } finally {
            api.stop();
            api.setEnabled(false);
        }
    }

    @Test
    public void realListenerRejectsMalformedOversizedAndStalledRequests() throws Exception {
        LoopbackAgentApi api = new LoopbackAgentApi(prompt -> true, msg -> {}, () -> TOKEN, 0);
        api.setEnabled(true);
        try {
            awaitPort(api);

            String oversizedHeader = "POST /v1/ask HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + TOKEN
                    + "\r\nContent-Length: " + (LoopbackAgentApi.MAX_BODY_BYTES + 1) + "\r\n\r\n";
            String oversized = sendRaw(api.port(), oversizedHeader, 3000);
            assertStartsWith(oversized, "HTTP/1.1 400");
            assertContentLength(oversized, "Malformed or oversized request");

            String malformedJson = "POST /v1/ask HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + TOKEN
                    + "\r\nContent-Type: application/json\r\nContent-Length: 10\r\n\r\n{\"prompt\":";
            String bad = sendRaw(api.port(), malformedJson, 3000);
            assertStartsWith(bad, "HTTP/1.1 400");
            assertContentLength(bad, "Missing or invalid prompt");

            long start = System.currentTimeMillis();
            try (Socket socket = new Socket("127.0.0.1", api.port())) {
                socket.getOutputStream().write(("POST /v1/ask HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer "
                        + TOKEN + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                socket.getOutputStream().flush();
                socket.setSoTimeout(6000);
                int read = socket.getInputStream().read();
                assertEquals("stalled client should be closed by server timeout", -1, read);
            }
            long elapsed = System.currentTimeMillis() - start;
            assertTrue("expected server read timeout, elapsed=" + elapsed + "ms", elapsed < 7000);
        } finally {
            api.stop();
            api.setEnabled(false);
        }
    }

    private static void awaitPort(LoopbackAgentApi api) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (api.isRunning() && api.port() > 0) return;
            Thread.sleep(25);
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
            try {
                int n;
                while ((n = in.read(buf)) >= 0) response.write(buf, 0, n);
            } catch (java.io.IOException ignored) {
                // Tolerate reset/close races after response bytes are written.
            }
            return new String(response.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void assertStartsWith(String response, String prefix) {
        assertNotNull(response);
        assertTrue("expected prefix '" + prefix + "' but got: " + response, response.startsWith(prefix));
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
        assertEquals("content-length", bodyBytes.length, len);
        assertTrue("body mismatch in: " + response, response.endsWith("\r\n\r\n" + expectedBody));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
