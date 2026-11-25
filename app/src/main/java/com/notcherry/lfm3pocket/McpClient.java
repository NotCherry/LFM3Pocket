package com.notcherry.lfm3pocket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight pure-Java MCP Streamable HTTP (JSON-RPC 2.0) client — zero external SDK bloat,
 * 100% F-Droid compatible. Supports local Termux loopback servers, LAN homelabs (Home Assistant,
 * n8n, MetaMCP), and remote HTTPS MCP endpoints (Composio MCP, GitHub MCP, Notion MCP).
 */
public final class McpClient {
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final int MAX_TOOLS_PER_SERVER = 64;
    private static final int MAX_TOOL_OUTPUT_CHARS = 12_000;
    private static final String PROTOCOL_VERSION = "2025-03-26";

    private McpClient() {}

    /** Performs MCP {@code initialize} + {@code tools/list} and returns discovered tools. */
    @SuppressWarnings("unchecked")
    public static List<McpServerConfig.ToolDef> listTools(McpServerConfig server) throws IOException {
        validateServer(server);
        String sessionId = initializeSession(server);
        String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}";
        RpcResponse resp = postJsonRpc(server, sessionId, body);
        Map<String, Object> result = requireResultObject(resp.json);
        Object rawTools = result.get("tools");
        if (!(rawTools instanceof List)) {
            return java.util.Collections.emptyList();
        }
        List<McpServerConfig.ToolDef> out = new ArrayList<>();
        for (Object item : (List<?>) rawTools) {
            if (out.size() >= MAX_TOOLS_PER_SERVER) break;
            if (!(item instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) item;
            String name = m.get("name") instanceof String ? ((String) m.get("name")).trim() : "";
            if (name.isEmpty()) continue;
            String desc = m.get("description") instanceof String ? ((String) m.get("description")).trim() : "";
            List<String> keys = new ArrayList<>();
            String schemaJson = "{\"type\":\"object\",\"properties\":{}}";
            Object rawSchema = m.get("inputSchema");
            if (rawSchema instanceof Map) {
                Map<String, Object> schemaMap = (Map<String, Object>) rawSchema;
                Object props = schemaMap.get("properties");
                if (props instanceof Map) {
                    for (Object k : ((Map<?, ?>) props).keySet()) {
                        if (k instanceof String && !((String) k).isEmpty() && keys.size() < 24) {
                            keys.add((String) k);
                        }
                    }
                }
                schemaJson = toCompactJson(schemaMap);
                if (schemaJson.length() > 2048) {
                    schemaJson = buildMinimalSchema(keys);
                }
            }
            out.add(new McpServerConfig.ToolDef(name, desc, keys, schemaJson));
        }
        return out;
    }

    /** Executes MCP {@code initialize} + {@code tools/call} and returns bounded text output. */
    @SuppressWarnings("unchecked")
    public static AndroidActionExecutor.ActionResult callTool(
            McpServerConfig server, String remoteToolName, Map<String, Object> arguments) throws IOException {
        validateServer(server);
        String sessionId = initializeSession(server);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", remoteToolName);
        params.put("arguments", arguments == null ? new LinkedHashMap<>() : arguments);
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("id", 3);
        req.put("method", "tools/call");
        req.put("params", params);

        RpcResponse resp = postJsonRpc(server, sessionId, toCompactJson(req));
        Map<String, Object> result = requireResultObject(resp.json);
        boolean isError = Boolean.TRUE.equals(result.get("isError"));
        String text = extractContentText(result.get("content"));
        if (text.isEmpty() && result.containsKey("structuredContent")) {
            text = toCompactJson(result.get("structuredContent"));
        }
        if (text.length() > MAX_TOOL_OUTPUT_CHARS) {
            text = text.substring(0, MAX_TOOL_OUTPUT_CHARS) + "… [truncated]";
        }
        if (isError) {
            return new AndroidActionExecutor.ActionResult(false, "MCP_TOOL_ERROR",
                    text.isEmpty() ? "MCP tool reported an error." : text);
        }
        return new AndroidActionExecutor.ActionResult(true, "MCP_OK",
                "UNTRUSTED_MCP " + (text.isEmpty() ? "Tool completed with no text output." : text));
    }

    private static void validateServer(McpServerConfig server) throws IOException {
        if (server == null) throw new IOException("Missing MCP server config");
        String err = McpEndpointPolicy.validationError(server.url, server.allowLanHttp);
        if (err != null) throw new IOException(err);
    }

    private static String initializeSession(McpServerConfig server) throws IOException {
        String initBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"" + PROTOCOL_VERSION + "\","
                + "\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"Hanami\",\"version\":\"1.0\"}}}";
        RpcResponse init = postJsonRpc(server, null, initBody);
        requireResultObject(init.json);
        // Best-effort initialized notification per MCP Streamable HTTP spec
        try {
            postNotificationBestEffort(server, init.sessionId,
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        } catch (IOException ignored) {}
        return init.sessionId;
    }

    private static final class RpcResponse {
        final Map<String, Object> json;
        final String sessionId;

        RpcResponse(Map<String, Object> json, String sessionId) {
            this.json = json;
            this.sessionId = sessionId;
        }
    }

    private static void postNotificationBestEffort(McpServerConfig server, String sessionId, String payload)
            throws IOException {
        HttpURLConnection conn = openConnection(server, sessionId);
        try {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
            conn.getResponseCode();
        } finally {
            conn.disconnect();
        }
    }

    @SuppressWarnings("unchecked")
    private static RpcResponse postJsonRpc(McpServerConfig server, String sessionId, String payload)
            throws IOException {
        HttpURLConnection conn = openConnection(server, sessionId);
        try {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
            int code = conn.getResponseCode();
            String respSession = conn.getHeaderField("Mcp-Session-Id");
            if (respSession == null || respSession.trim().isEmpty()) respSession = sessionId;
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String body = readBounded(stream, MAX_RESPONSE_BYTES);
            if (code < 200 || code >= 300) {
                throw new IOException("MCP server returned HTTP " + code
                        + (body.isEmpty() ? "" : ": " + truncate(body, 160)));
            }
            String contentType = conn.getContentType() == null ? "" : conn.getContentType().toLowerCase(Locale.ROOT);
            String jsonText = contentType.contains("text/event-stream") || body.startsWith("event:") || body.startsWith("data:")
                    ? extractSseJsonPayload(body)
                    : body.trim();
            if (jsonText.isEmpty()) {
                throw new IOException("MCP server returned an empty JSON-RPC response.");
            }
            Object parsed;
            try {
                parsed = StrictToolJson.parseBounded(jsonText, MAX_RESPONSE_BYTES, 12, 256);
            } catch (IllegalArgumentException e) {
                throw new IOException("MCP server returned invalid or oversized JSON.");
            }
            if (!(parsed instanceof Map)) {
                throw new IOException("MCP server response must be a JSON object.");
            }
            return new RpcResponse((Map<String, Object>) parsed, respSession);
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection openConnection(McpServerConfig server, String sessionId) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(server.url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json, text/event-stream");
        conn.setRequestProperty("User-Agent", "Hanami/1.0 (Android; MCP-Client)");
        if (server.bearerToken != null && !server.bearerToken.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + server.bearerToken);
        }
        if (sessionId != null && !sessionId.isEmpty()) {
            conn.setRequestProperty("Mcp-Session-Id", sessionId);
        }
        return conn;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireResultObject(Map<String, Object> rpc) throws IOException {
        Object err = rpc.get("error");
        if (err instanceof Map) {
            Object msg = ((Map<String, Object>) err).get("message");
            throw new IOException("MCP error: " + (msg == null ? err.toString() : String.valueOf(msg)));
        }
        Object result = rpc.get("result");
        if (!(result instanceof Map)) {
            throw new IOException("MCP response missing result object.");
        }
        return (Map<String, Object>) result;
    }

    @SuppressWarnings("unchecked")
    private static String extractContentText(Object rawContent) {
        if (!(rawContent instanceof List)) return "";
        StringBuilder sb = new StringBuilder();
        for (Object item : (List<?>) rawContent) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) item;
            Object text = m.get("text");
            if (text instanceof String && !((String) text).isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append((String) text);
            }
        }
        return sb.toString().trim();
    }

    static String extractSseJsonPayload(String sse) {
        String lastData = "";
        String[] lines = sse.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("data:")) {
                String data = trimmed.substring(5).trim();
                if (data.startsWith("{") && data.endsWith("}")) {
                    lastData = data;
                }
            }
        }
        return lastData;
    }

    private static String readBounded(InputStream in, int maxBytes) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            if (total + n > maxBytes) {
                throw new IOException("MCP response exceeded " + (maxBytes / 1024) + " KB limit.");
            }
            out.write(buf, 0, n);
            total += n;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String buildMinimalSchema(List<String> keys) {
        StringBuilder sb = new StringBuilder("{\"type\":\"object\",\"properties\":{");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(CustomShortcut.escapeJson(keys.get(i))).append("\":{\"type\":\"string\"}");
        }
        sb.append("}}");
        return sb.toString();
    }

    static String toCompactJson(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return String.valueOf(value);
        if (value instanceof String) return "\"" + CustomShortcut.escapeJson((String) value) + "\"";
        if (value instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) sb.append(',');
                sb.append('"').append(CustomShortcut.escapeJson(String.valueOf(entry.getKey()))).append("\":");
                sb.append(toCompactJson(entry.getValue()));
                first = false;
            }
            return sb.append('}').toString();
        }
        if (value instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            List<?> list = (List<?>) value;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(toCompactJson(list.get(i)));
            }
            return sb.append(']').toString();
        }
        return "\"" + CustomShortcut.escapeJson(String.valueOf(value)) + "\"";
    }

    private static String truncate(String s, int max) {
        String clean = s.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "…";
    }
}
