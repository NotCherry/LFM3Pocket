package com.notcherry.lfm3pocket;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class WebSearchTest {
    private ServerSocket serverSocket;
    private Thread serverThread;
    private String baseUrl;
    private volatile Handler handler;

    private interface Handler {
        Response handle(Request request);
    }

    private static final class Request {
        final String method;
        final String path;
        final String query;
        final Map<String, String> headers;
        final String body;

        Request(String method, String path, String query, Map<String, String> headers, String body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.body = body;
        }
    }

    private static final class Response {
        final int code;
        final String body;
        final String retryAfter;

        Response(int code, String body, String retryAfter) {
            this.code = code;
            this.body = body;
            this.retryAfter = retryAfter;
        }
    }

    @Before public void startServer() throws IOException {
        serverSocket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        baseUrl = "http://127.0.0.1:" + serverSocket.getLocalPort();
        serverThread = new Thread(() -> {
            while (!serverSocket.isClosed()) {
                try (Socket socket = serverSocket.accept()) {
                    handleConnection(socket);
                } catch (IOException ignored) {
                    if (serverSocket.isClosed()) break;
                }
            }
        }, "web-search-test-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    @After public void stopServer() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
    }

    @Test public void searxngSendsAcceptLanguageAndOptionalBearerToken() throws Exception {
        AtomicReference<Request> seen = new AtomicReference<>();
        handler = req -> {
            seen.set(req);
            return new Response(200,
                    "{\"results\":[{\"title\":\"Hanami\",\"content\":\"Local AI\",\"url\":\"https://example.org/hanami\"}]}",
                    null);
        };

        SearchConfig cfg = new SearchConfig(SearchProvider.SEARXNG, baseUrl, "secret-token", true);
        List<WebSearch.Source> results = WebSearch.search(cfg, "hanami", "week", new Locale("pl", "PL"));

        assertEquals(1, results.size());
        assertEquals("Hanami", results.get(0).title);
        assertEquals("Local AI", results.get(0).snippet);
        assertEquals("https://example.org/hanami", results.get(0).url);
        Request req = seen.get();
        assertNotNull(req);
        assertEquals("pl-PL,pl;q=0.9,en;q=0.5", req.headers.get("accept-language"));
        assertEquals("Bearer secret-token", req.headers.get("authorization"));
        assertTrue(req.query.contains("format=json"));
        assertTrue(req.query.contains("time_range=week"));
    }

    @Test public void searxng429IncludesRetryAfterAndActionableGuidance() throws Exception {
        handler = req -> new Response(429, "Too Many Requests", "60");

        try {
            WebSearch.search(baseUrl + "/search", "test");
            fail("Expected HTTP 429 exception");
        } catch (Exception e) {
            String msg = e.getMessage();
            assertNotNull(msg);
            assertTrue(msg.contains("HTTP 429"));
            assertTrue(msg.contains("Retry-After: 60"));
            assertTrue(msg.contains("Public SearXNG instances block JSON API clients"));
        }
    }

    @Test public void wikipediaParsesResultsAndStripsHtmlSnippets() throws Exception {
        AtomicReference<Request> seen = new AtomicReference<>();
        handler = req -> {
            seen.set(req);
            String json = "{\"query\":{\"search\":["
                    + "{\"title\":\"Liquid AI\",\"pageid\":4242,\"snippet\":\"<span class=\\\"searchmatch\\\">Liquid</span> &amp; &quot;fast&quot; models.\"}"
                    + "]}}";
            return new Response(200, json, null);
        };

        List<WebSearch.Source> results = WebSearch.searchWikipediaAt(
                baseUrl + "/w/api.php", "pl", "Liquid AI", new Locale("pl", "PL"));

        assertEquals(1, results.size());
        assertEquals("Liquid AI", results.get(0).title);
        assertEquals("Liquid & \"fast\" models.", results.get(0).snippet);
        assertEquals("https://pl.wikipedia.org/?curid=4242", results.get(0).url);
        Request req = seen.get();
        assertNotNull(req);
        assertTrue(req.query.contains("action=query"));
        assertTrue(req.query.contains("list=search"));
        assertTrue(req.query.contains("format=json"));
    }

    @Test public void braveSendsSubscriptionTokenAndFreshnessAndParsesFallbackSnippet() throws Exception {
        AtomicReference<Request> seen = new AtomicReference<>();
        handler = req -> {
            seen.set(req);
            String json = "{\"web\":{\"results\":["
                    + "{\"title\":\"<b>Brave</b> Result\",\"url\":\"https://brave.example/1\",\"description\":\"Primary snippet\"},"
                    + "{\"title\":\"Second\",\"url\":\"https://brave.example/2\",\"description\":\"\",\"extra_snippets\":[\"Fallback <i>snippet</i>\"]}"
                    + "]}}";
            return new Response(200, json, null);
        };

        List<WebSearch.Source> results = WebSearch.searchBrave(
                baseUrl + "/res/v1/web/search", "BSA-key-123", "android", "week", Locale.US);

        Request req = seen.get();
        assertNotNull(req);
        assertEquals("BSA-key-123", req.headers.get("x-subscription-token"));
        assertTrue(req.query.contains("freshness=pw"));
        assertTrue(req.query.contains("count=5"));
        assertEquals(2, results.size());
        assertEquals("Brave Result", results.get(0).title);
        assertEquals("Primary snippet", results.get(0).snippet);
        assertEquals("Fallback snippet", results.get(1).snippet);
    }

    @Test public void tavilyPostsJsonPayloadWithBearerAuth() throws Exception {
        AtomicReference<Request> seen = new AtomicReference<>();
        handler = req -> {
            seen.set(req);
            String json = "{\"results\":[{\"title\":\"Tavily Hit\",\"url\":\"https://tavily.example/hit\",\"content\":\"Clean LLM snippet\"}]}";
            return new Response(200, json, null);
        };

        List<WebSearch.Source> results = WebSearch.searchTavily(
                baseUrl + "/search", "tvly-secret", "LFM3", "day", Locale.US);

        Request req = seen.get();
        assertNotNull(req);
        assertEquals("POST", req.method);
        assertEquals("Bearer tvly-secret", req.headers.get("authorization"));
        assertTrue(req.body.contains("\"query\":\"LFM3\""));
        assertTrue(req.body.contains("\"time_range\":\"day\""));
        assertEquals(1, results.size());
        assertEquals("Tavily Hit", results.get(0).title);
        assertEquals("Clean LLM snippet", results.get(0).snippet);
        assertEquals("https://tavily.example/hit", results.get(0).url);
    }

    @Test public void helperFormattingAndMapping() {
        assertEquals("pl-PL,pl;q=0.9,en;q=0.5", WebSearch.buildAcceptLanguageHeader(new Locale("pl", "PL")));
        assertEquals("en-US,en;q=0.9", WebSearch.buildAcceptLanguageHeader(Locale.US));
        assertEquals("de,en;q=0.5", WebSearch.buildAcceptLanguageHeader(Locale.GERMAN));
        assertEquals("pl", WebSearch.wikipediaLanguage(new Locale("pl", "PL")));
        assertEquals("en", WebSearch.wikipediaLanguage(null));
        assertEquals("pd", WebSearch.mapBraveFreshness("day"));
        assertEquals("pw", WebSearch.mapBraveFreshness("week"));
        assertEquals("pm", WebSearch.mapBraveFreshness("month"));
        assertEquals("py", WebSearch.mapBraveFreshness("year"));
        assertNull(WebSearch.mapBraveFreshness("invalid"));
        assertEquals("month", WebSearch.normalizeTavilyTimeRange("MONTH"));
        assertNull(WebSearch.normalizeTavilyTimeRange("forever"));
    }

    @Test public void builtinToolsExecutorSupportsNonSearxngProviders() throws Exception {
        AtomicReference<SearchProvider> calledProvider = new AtomicReference<>();
        AtomicReference<String> calledQuery = new AtomicReference<>();
        SearchConfig wikiEnabled = new SearchConfig(SearchProvider.WIKIPEDIA, "", "", true);
        BuiltinToolsExecutor executor = new BuiltinToolsExecutor(
                () -> false,
                url -> "",
                () -> wikiEnabled,
                (cfg, query, timeRange) -> {
                    calledProvider.set(cfg.provider);
                    calledQuery.set(query);
                    return Collections.singletonList(
                            new WebSearch.Source("Hanami", "Local assistant", "https://en.wikipedia.org/?curid=1"));
                });
        AndroidActionExecutor.ActionResult result = executor.execute("web_search",
                Collections.singletonMap("query", "Hanami"));
        assertTrue(result.succeeded);
        assertEquals("WEB_SEARCH_RESULT", result.status);
        assertEquals(SearchProvider.WIKIPEDIA, calledProvider.get());
        assertEquals("Hanami", calledQuery.get());
        assertTrue(result.message.contains("UNTRUSTED_WEB_SEARCH"));
    }

    private void handleConnection(Socket socket) throws IOException {
        socket.setSoTimeout(3000);
        InputStream in = new BufferedInputStream(socket.getInputStream());
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) return;
        String[] parts = requestLine.split(" ");
        String method = parts.length > 0 ? parts[0] : "GET";
        String target = parts.length > 1 ? parts[1] : "/";
        int qIdx = target.indexOf('?');
        String path = qIdx >= 0 ? target.substring(0, qIdx) : target;
        String query = qIdx >= 0 ? target.substring(qIdx + 1) : "";

        Map<String, String> headers = new LinkedHashMap<>();
        int contentLength = 0;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                headers.put(name, value);
                if ("content-length".equals(name)) {
                    contentLength = Integer.parseInt(value);
                }
            }
        }
        String body = "";
        if (contentLength > 0) {
            byte[] buf = new byte[contentLength];
            int offset = 0;
            while (offset < contentLength) {
                int read = in.read(buf, offset, contentLength - offset);
                if (read < 0) break;
                offset += read;
            }
            body = new String(buf, 0, offset, StandardCharsets.UTF_8);
        }

        Handler h = handler;
        Response resp = h == null ? new Response(500, "No handler", null)
                : h.handle(new Request(method, path, query, headers, body));
        byte[] payload = resp.body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(resp.code).append(" OK\r\n");
        head.append("Content-Type: application/json; charset=utf-8\r\n");
        if (resp.retryAfter != null) {
            head.append("Retry-After: ").append(resp.retryAfter).append("\r\n");
        }
        head.append("Content-Length: ").append(payload.length).append("\r\n");
        head.append("Connection: close\r\n\r\n");

        OutputStream out = socket.getOutputStream();
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int prev = -1;
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] bytes = out.toByteArray();
                int len = bytes.length;
                if (prev == '\r' && len > 0) len--;
                return new String(bytes, 0, len, StandardCharsets.US_ASCII);
            }
            out.write(b);
            prev = b;
        }
        return out.size() == 0 ? null : out.toString(StandardCharsets.US_ASCII.name());
    }
}
