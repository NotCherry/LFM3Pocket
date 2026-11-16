package com.notcherry.lfm3pocket;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Opt-in HTTP listener on {@code 127.0.0.1}. It can submit a prompt. It cannot
 * approve a tool. External text over this socket is never a {@code USER_ACTION}.
 */
public final class LoopbackAgentApi {
    public static final int DEFAULT_PORT = 17834;

    public interface PromptHandler {
        /** @return false when the runtime refused the turn */
        boolean submit(String prompt);
    }

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final PromptHandler prompts;
    private final Consumer<String> log;
    private final ExecutorService acceptors = Executors.newSingleThreadExecutor();
    private final ExecutorService clients = Executors.newCachedThreadPool();
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
        } catch (IOException error) {
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

    /** Pure routing used by tests and by the socket handler. */
    static String handle(String method, String path, String body, PromptHandler prompts) {
        String verb = method == null ? "" : method.toUpperCase(Locale.ROOT);
        String route = path == null ? "" : path;
        if ("POST".equals(verb) && "/v1/approve".equals(route)) {
            return "HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: 47\r\nConnection: close\r\n\r\n"
                    + "Loopback cannot approve Android actions.";
        }
        if ("POST".equals(verb) && "/v1/ask".equals(route)) {
            String prompt = extractPrompt(body);
            if (prompt == null || prompt.isBlank()) {
                return text(400, "Missing prompt");
            }
            boolean accepted = prompts != null && prompts.submit(prompt);
            return accepted ? text(202, "Accepted") : text(409, "Runtime refused the turn");
        }
        if ("GET".equals(verb) && "/health".equals(route)) {
            return text(200, "ok");
        }
        return text(404, "Not found");
    }

    private void acceptLoop(ServerSocket socket) {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                clients.execute(() -> serve(client));
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void serve(Socket client) {
        try (Socket socket = client;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = socket.getOutputStream()) {
            String request = in.readLine();
            if (request == null || request.isBlank()) return;
            String[] parts = request.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "";
            int contentLength = 0;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }
            char[] bodyChars = new char[Math.max(0, contentLength)];
            int read = 0;
            while (read < bodyChars.length) {
                int n = in.read(bodyChars, read, bodyChars.length - read);
                if (n < 0) break;
                read += n;
            }
            String body = new String(bodyChars, 0, read);
            byte[] response = handle(method, path, body, prompts).getBytes(StandardCharsets.UTF_8);
            out.write(response);
            out.flush();
        } catch (Exception ignored) {
        }
    }

    private static String extractPrompt(String body) {
        if (body == null) return null;
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            int key = trimmed.indexOf("\"prompt\"");
            if (key < 0) return null;
            int colon = trimmed.indexOf(':', key);
            int first = trimmed.indexOf('"', colon + 1);
            int second = trimmed.indexOf('"', first + 1);
            if (first < 0 || second < 0) return null;
            return trimmed.substring(first + 1, second);
        }
        return trimmed;
    }

    private static String text(int code, String message) {
        String reason = code == 200 ? "OK" : code == 202 ? "Accepted" : code == 400 ? "Bad Request"
                : code == 403 ? "Forbidden" : code == 409 ? "Conflict" : "Error";
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        return "HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n" + message;
    }
}
