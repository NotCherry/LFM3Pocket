package com.notcherry.lfm3pocket;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Opt-in HTTP listener bound exclusively to 127.0.0.1. It can submit a prompt,
 * but can never approve a tool or create USER_ACTION authority.
 */
public final class LoopbackAgentApi {
    public static final int DEFAULT_PORT = 17834;
    static final int MAX_HEADER_BYTES = 8 * 1024;
    static final int MAX_BODY_BYTES = 16 * 1024;
    private static final int SOCKET_TIMEOUT_MS = 3000;

    public interface PromptHandler {
        /** @return false when the runtime refused the turn */
        boolean submit(String prompt);
    }

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final PromptHandler prompts;
    private final Consumer<String> log;
    private final ThreadPoolExecutor acceptors = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
    private final ThreadPoolExecutor clients = new ThreadPoolExecutor(
            2, 4, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
            runnable -> {
                Thread thread = new Thread(runnable, "loopback-api-client");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private volatile ServerSocket server;
    private volatile int port = DEFAULT_PORT;

    public LoopbackAgentApi(PromptHandler prompts, Consumer<String> log) {
        this.prompts = prompts;
        this.log = log == null ? message -> {} : log;
    }

    public synchronized boolean isRunning() {
        return server != null && !server.isClosed();
    }

    public int port() { return port; }

    public synchronized void setEnabled(boolean on) {
        enabled.set(on);
        if (on) start();
        else stop();
    }

    public synchronized void start() {
        if (!enabled.get() || isRunning()) return;
        try {
            ServerSocket socket = new ServerSocket(DEFAULT_PORT, 8, InetAddress.getByName("127.0.0.1"));
            server = socket;
            port = socket.getLocalPort();
            acceptors.execute(() -> acceptLoop(socket));
            log.accept("Loopback API listening on 127.0.0.1:" + port);
        } catch (IOException | java.util.concurrent.RejectedExecutionException error) {
            server = null;
            log.accept("Loopback API failed to bind: " + error.getMessage());
        }
    }

    public synchronized void stop() {
        ServerSocket socket = server;
        server = null;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    /** Pure routing used by tests and by the bounded socket handler. */
    static String handle(String method, String path, String body, PromptHandler prompts) {
        String verb = method == null ? "" : method.toUpperCase(Locale.ROOT);
        String route = path == null ? "" : path;
        if ("POST".equals(verb) && "/v1/approve".equals(route)) {
            return text(403, "Loopback cannot approve Android actions.");
        }
        if ("POST".equals(verb) && "/v1/ask".equals(route)) {
            String prompt = extractPrompt(body);
            if (prompt == null || prompt.isBlank()) return text(400, "Missing or invalid prompt");
            boolean accepted = prompts != null && prompts.submit(prompt);
            return accepted ? text(202, "Accepted") : text(409, "Runtime refused the turn");
        }
        if ("GET".equals(verb) && "/health".equals(route)) return text(200, "ok");
        return text(404, "Not found");
    }

    private void acceptLoop(ServerSocket socket) {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                if (!client.getInetAddress().isLoopbackAddress()) {
                    client.close();
                    continue;
                }
                try {
                    clients.execute(() -> serve(client));
                } catch (java.util.concurrent.RejectedExecutionException overloaded) {
                    writeErrorAndClose(client, 503, "Server busy");
                }
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(SOCKET_TIMEOUT_MS);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            Request request = readRequest(in);
            String response = request == null
                    ? text(400, "Malformed or oversized request")
                    : handle(request.method, request.path, request.body, prompts);
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
            // A malformed, timed-out, or disconnected local client must not affect the accept loop.
        }
    }

    private static Request readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int matched = 0;
        while (header.size() < MAX_HEADER_BYTES) {
            int value = in.read();
            if (value < 0) return null;
            header.write(value);
            if ((matched == 0 && value == '\r') || (matched == 1 && value == '\n')
                    || (matched == 2 && value == '\r') || (matched == 3 && value == '\n')) {
                matched++;
                if (matched == 4) break;
            } else {
                matched = value == '\r' ? 1 : 0;
            }
        }
        if (matched != 4) return null;
        String[] lines = header.toString(StandardCharsets.ISO_8859_1).split("\\r\\n");
        String[] requestLine = lines[0].split(" ", -1);
        if (requestLine.length != 3 || !("HTTP/1.1".equals(requestLine[2]) || "HTTP/1.0".equals(requestLine[2]))
                || requestLine[0].isEmpty() || !requestLine[1].startsWith("/")
                || requestLine[1].contains("#") || requestLine[1].indexOf('\\') >= 0) return null;

        int contentLength = 0;
        boolean seenLength = false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) break;
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') return null;
            int colon = line.indexOf(':');
            if (colon <= 0) return null;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if ("transfer-encoding".equals(name)) return null;
            if ("content-length".equals(name)) {
                if (seenLength || value.isEmpty()) return null;
                seenLength = true;
                try { contentLength = Integer.parseInt(value); }
                catch (NumberFormatException invalid) { return null; }
                if (contentLength < 0 || contentLength > MAX_BODY_BYTES) return null;
            }
        }
        byte[] bodyBytes = new byte[contentLength];
        int offset = 0;
        while (offset < bodyBytes.length) {
            int count = in.read(bodyBytes, offset, bodyBytes.length - offset);
            if (count < 0) return null;
            offset += count;
        }
        return new Request(requestLine[0], requestLine[1], new String(bodyBytes, StandardCharsets.UTF_8));
    }

    private static void writeErrorAndClose(Socket socket, int status, String message) {
        try (Socket client = socket; OutputStream out = client.getOutputStream()) {
            client.setSoTimeout(SOCKET_TIMEOUT_MS);
            out.write(text(status, message).getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {}
    }

    private static String extractPrompt(String body) {
        if (body == null) return null;
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            try {
                JSONObject json = new JSONObject(trimmed);
                Object value = json.opt("prompt");
                return value instanceof String ? (String) value : null;
            } catch (JSONException invalid) {
                return null;
            }
        }
        return trimmed;
    }

    private static String text(int code, String message) {
        String reason = code == 200 ? "OK" : code == 202 ? "Accepted" : code == 400 ? "Bad Request"
                : code == 403 ? "Forbidden" : code == 409 ? "Conflict"
                : code == 503 ? "Service Unavailable" : "Not Found";
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        return "HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n" + message;
    }

    private static final class Request {
        final String method;
        final String path;
        final String body;
        Request(String method, String path, String body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }
    }
}
