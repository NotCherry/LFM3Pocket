package com.notcherry.lfm3pocket;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Opt-in, read-only local Wi-Fi HTTP diagnostics viewer so developers/users can inspect live
 * sanitized processing traces and crash logs from a PC browser on the same LAN without ADB.
 *
 * <p>Off by default; protected by a fresh per-session random URL token; serves read-only GET
 * endpoints only ({@code /} HTML auto-refresh view, {@code /raw} plain Markdown, {@code /ndjson}).
 */
public final class LanDiagnosticsServer {
    private final Supplier<String> reportMarkdownSupplier;
    private final Supplier<String> ndjsonSupplier;
    private final int requestedPort;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "hanami-lan-diag");
        t.setDaemon(true);
        return t;
    });

    private volatile ServerSocket serverSocket;
    private volatile String sessionToken = "";
    private volatile int boundPort;

    public LanDiagnosticsServer(Supplier<String> reportMarkdownSupplier, Supplier<String> ndjsonSupplier) {
        this(reportMarkdownSupplier, ndjsonSupplier, 0);
    }

    public LanDiagnosticsServer(Supplier<String> reportMarkdownSupplier,
                                Supplier<String> ndjsonSupplier,
                                int requestedPort) {
        if (reportMarkdownSupplier == null) throw new IllegalArgumentException("Missing report supplier");
        this.reportMarkdownSupplier = reportMarkdownSupplier;
        this.ndjsonSupplier = ndjsonSupplier == null ? () -> "" : ndjsonSupplier;
        this.requestedPort = Math.max(0, requestedPort);
    }

    public synchronized boolean isRunning() {
        return serverSocket != null && !serverSocket.isClosed();
    }

    public int port() { return boundPort; }

    public String token() { return sessionToken; }

    /** Starts listening on all local interfaces with a fresh random token and returns the LAN URL. */
    public synchronized String start() throws IOException {
        if (isRunning()) return displayUrl();
        byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        sessionToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        ServerSocket socket = new ServerSocket(requestedPort);
        serverSocket = socket;
        boundPort = socket.getLocalPort();
        executor.execute(() -> acceptLoop(socket));
        return displayUrl();
    }

    public synchronized void stop() {
        ServerSocket socket = serverSocket;
        serverSocket = null;
        boundPort = 0;
        sessionToken = "";
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    public String displayUrl() {
        if (!isRunning()) return "";
        String host = findLocalLanIpv4();
        return "http://" + host + ":" + boundPort + "/?token=" + sessionToken;
    }

    private void acceptLoop(ServerSocket socket) {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                executor.execute(() -> handleClient(client));
            } catch (IOException ignored) {
                break;
            }
        }
    }

    private void handleClient(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(4000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String target = parts[1];
            // Drain headers up to 32 lines
            for (int i = 0; i < 32; i++) {
                String line = reader.readLine();
                if (line == null || line.isEmpty()) break;
            }
            if (!"GET".equalsIgnoreCase(method)) {
                writeResponse(s.getOutputStream(), 405, "text/plain; charset=utf-8", "Method Not Allowed (read-only GET only)\n");
                return;
            }
            String path = target;
            String query = "";
            int qIdx = target.indexOf('?');
            if (qIdx >= 0) {
                path = target.substring(0, qIdx);
                query = target.substring(qIdx + 1);
            }
            String provided = extractQueryParam(query, "token");
            if (!constantTimeEquals(sessionToken, provided)) {
                writeResponse(s.getOutputStream(), 403, "text/plain; charset=utf-8", "Forbidden: missing or invalid token\n");
                return;
            }
            if ("/raw".equals(path)) {
                String md = DiagnosticsReportBuilder.redactSecrets(reportMarkdownSupplier.get());
                writeResponse(s.getOutputStream(), 200, "text/plain; charset=utf-8", md);
            } else if ("/ndjson".equals(path)) {
                String nd = DiagnosticsReportBuilder.redactSecrets(ndjsonSupplier.get());
                writeResponse(s.getOutputStream(), 200, "application/x-ndjson; charset=utf-8", nd);
            } else {
                String md = DiagnosticsReportBuilder.redactSecrets(reportMarkdownSupplier.get());
                String html = "<!doctype html><html><head><meta charset='utf-8'>"
                        + "<meta http-equiv='refresh' content='5'>"
                        + "<title>Hanami Local Diagnostics</title>"
                        + "<style>body{background:#121316;color:#ece7df;font-family:monospace;padding:20px;line-height:1.45;}"
                        + "a{color:#e0a96d;}pre{white-space:pre-wrap;background:#1c1e24;padding:16px;border-radius:8px;border:1px solid #2d3039;}</style>"
                        + "</head><body><h2>Hanami On-Device Diagnostics (auto-refresh 5s)</h2>"
                        + "<p><a href='/raw?token=" + sessionToken + "'>Raw Markdown</a> · "
                        + "<a href='/ndjson?token=" + sessionToken + "'>NDJSON Events</a></p>"
                        + "<pre>" + escapeHtml(md) + "</pre></body></html>";
                writeResponse(s.getOutputStream(), 200, "text/html; charset=utf-8", html);
            }
        } catch (Throwable ignored) {}
    }

    private static void writeResponse(OutputStream out, int status, String contentType, String body) throws IOException {
        byte[] payload = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : status == 403 ? "Forbidden" : "Method Not Allowed";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();
    }

    private static String extractQueryParam(String query, String key) {
        if (query == null || query.isEmpty()) return "";
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && key.equals(pair.substring(0, eq))) {
                return pair.substring(eq + 1);
            }
        }
        return "";
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.isEmpty()) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String findLocalLanIpv4() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface nif = ifaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "127.0.0.1";
    }
}
