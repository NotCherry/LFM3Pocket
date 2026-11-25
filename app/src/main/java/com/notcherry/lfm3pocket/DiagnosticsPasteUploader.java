package com.notcherry.lfm3pocket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Explicit user-triggered, zero-cost paste upload for sanitized diagnostic logs.
 *
 * <p>Privacy contract: never called in the background; invoked only after the user explicitly taps
 * "Upload sanitized log & open GitHub Issue" and confirms the redaction preview dialog.
 * Strictly HTTPS-only; tries {@code dpaste.org} (7-day auto-expiry) with fallback to {@code paste.rs}.
 */
public final class DiagnosticsPasteUploader {
    public static final String DPASTE_ENDPOINT = "https://dpaste.org/api/";
    public static final String PASTE_RS_ENDPOINT = "https://paste.rs/";
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 12_000;
    private static final int MAX_UPLOAD_CHARS = 48_000;
    private static final int MAX_RESPONSE_BYTES = 4_096;

    public interface Transport {
        String post(String url, String contentType, byte[] body) throws IOException;
    }

    public static final Transport HTTP_TRANSPORT = (endpoint, contentType, body) -> {
        if (endpoint == null || !endpoint.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IOException("Paste upload requires HTTPS.");
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Content-Type", contentType);
        conn.setRequestProperty("User-Agent", "Hanami/1.0 (Android; Diagnostics)");
        try {
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String response = readBounded(stream, MAX_RESPONSE_BYTES).trim();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + (response.isEmpty() ? "" : ": " + response));
            }
            return response;
        } finally {
            conn.disconnect();
        }
    };

    private DiagnosticsPasteUploader() {}

    /** Uploads sanitized text via default HTTPS paste services and returns the resulting HTTPS URL. */
    public static String upload(String rawReport) throws IOException {
        return upload(rawReport, HTTP_TRANSPORT);
    }

    public static String upload(String rawReport, Transport transport) throws IOException {
        if (transport == null) throw new IOException("Missing HTTP transport");
        String sanitized = DiagnosticsReportBuilder.redactSecrets(rawReport == null ? "" : rawReport).trim();
        if (sanitized.isEmpty()) throw new IOException("Diagnostics report is empty.");
        if (sanitized.length() > MAX_UPLOAD_CHARS) {
            sanitized = sanitized.substring(0, MAX_UPLOAD_CHARS) + "\n…(truncated)";
        }

        IOException firstError;
        try {
            String form = "content=" + URLEncoder.encode(sanitized, StandardCharsets.UTF_8.name())
                    + "&syntax=text&expiry_days=7";
            String url = parseReturnedUrl(transport.post(
                    DPASTE_ENDPOINT,
                    "application/x-www-form-urlencoded; charset=utf-8",
                    form.getBytes(StandardCharsets.UTF_8)));
            if (url != null) return url;
            firstError = new IOException("dpaste.org returned unexpected response.");
        } catch (IOException e) {
            firstError = e;
        }

        try {
            String url = parseReturnedUrl(transport.post(
                    PASTE_RS_ENDPOINT,
                    "text/plain; charset=utf-8",
                    sanitized.getBytes(StandardCharsets.UTF_8)));
            if (url != null) return url;
            throw new IOException("paste.rs returned unexpected response.");
        } catch (IOException secondError) {
            throw new IOException("Upload failed (dpaste: " + firstError.getMessage()
                    + "; paste.rs: " + secondError.getMessage() + ")");
        }
    }

    static String parseReturnedUrl(String response) {
        if (response == null) return null;
        String trimmed = response.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() > 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        int nl = trimmed.indexOf('\n');
        if (nl > 0) trimmed = trimmed.substring(0, nl).trim();
        if (trimmed.startsWith("https://") && trimmed.length() < 256 && !trimmed.contains(" ")) {
            return trimmed;
        }
        return null;
    }

    private static String readBounded(InputStream in, int maxBytes) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            int take = Math.min(n, maxBytes - total);
            if (take > 0) {
                out.write(buf, 0, take);
                total += take;
            }
            if (total >= maxBytes) break;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
