package com.notcherry.lfm3pocket;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class WebSearchTest {
    private HttpServer server;
    private String baseUrl;

    @Before public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After public void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test public void searxngSendsAcceptLanguageAndOptionalBearerToken() throws Exception {
        AtomicReference<String> seenAcceptLang = new AtomicReference<>();
        AtomicReference<String> seenAuth = new AtomicReference<>();
        AtomicReference<String> seenQuery = new AtomicReference<>();
        server.createContext("/search", exchange -> {
            Headers h = exchange.getRequestHeaders();
            seenAcceptLang.set(h.getFirst("Accept-Language"));
            seenAuth.set(h.getFirst("Authorization"));
            seenQuery.set(exchange.getRequestURI().getQuery());
            reply(exchange, 200,
                    "{\"results\":[{\"title\":\"Hanami\",\"content\":\"Local AI\",\"url\":\"https://example.org/hanami\"}]}",
                    null);
        });
        server.start();

        SearchConfig cfg = new SearchConfig(SearchProvider.SEARXNG, baseUrl, "secret-token", true);
        List<WebSearch.Source> results = WebSearch.search(cfg, "hanami", "week", new Locale("pl", "PL"));

        assertEquals(1, results.size());
        assertEquals("Hanami", results.get(0).title);
        assertEquals("Local AI", results.get(0).snippet);
        assertEquals("https://example.org/hanami", results.get(0).url);
        assertEquals("pl-PL,pl;q=0.9,en;q=0.5", seenAcceptLang.get());
        assertEquals("Bearer secret-token", seenAuth.get());
        assertNotNull(seenQuery.get());
        assertTrue(seenQuery.get().contains("format=json"));
        assertTrue(seenQuery.get().contains("time_range=week"));
    }

    @Test public void searxng429IncludesRetryAfterAndActionableGuidance() throws Exception {
        server.createContext("/search", exchange -> reply(exchange, 429, "Too Many Requests", "60"));
        server.start();

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
        AtomicReference<String> seenQuery = new AtomicReference<>();
        server.createContext("/w/api.php", exchange -> {
            seenQuery.set(exchange.getRequestURI().getQuery());
            String json = "{\"query\":{\"search\":["
                    + "{\"title\":\"Liquid AI\",\"pageid\":4242,\"snippet\":\"<span class=\\\"searchmatch\\\">Liquid</span> &amp; &quot;fast&quot; models.\"}"
                    + "]}}";
            reply(exchange, 200, json, null);
        });
        server.start();

        List<WebSearch.Source> results = WebSearch.searchWikipediaAt(
                baseUrl + "/w/api.php", "pl", "Liquid AI", new Locale("pl", "PL"));

        assertEquals(1, results.size());
        assertEquals("Liquid AI", results.get(0).title);
        assertEquals("Liquid & \"fast\" models.", results.get(0).snippet);
        assertEquals("https://pl.wikipedia.org/?curid=4242", results.get(0).url);
        assertNotNull(seenQuery.get());
        assertTrue(seenQuery.get().contains("action=query"));
        assertTrue(seenQuery.get().contains("list=search"));
        assertTrue(seenQuery.get().contains("format=json"));
    }

    @Test public void braveSendsSubscriptionTokenAndFreshnessAndParsesFallbackSnippet() throws Exception {
        AtomicReference<String> seenToken = new AtomicReference<>();
        AtomicReference<String> seenQuery = new AtomicReference<>();
        server.createContext("/res/v1/web/search", exchange -> {
            seenToken.set(exchange.getRequestHeaders().getFirst("X-Subscription-Token"));
            seenQuery.set(exchange.getRequestURI().getQuery());
            String json = "{\"web\":{\"results\":["
                    + "{\"title\":\"<b>Brave</b> Result\",\"url\":\"https://brave.example/1\",\"description\":\"Primary snippet\"},"
                    + "{\"title\":\"Second\",\"url\":\"https://brave.example/2\",\"description\":\"\",\"extra_snippets\":[\"Fallback <i>snippet</i>\"]}"
                    + "]}}";
            reply(exchange, 200, json, null);
        });
        server.start();

        List<WebSearch.Source> results = WebSearch.searchBrave(
                baseUrl + "/res/v1/web/search", "BSA-key-123", "android", "week", Locale.US);

        assertEquals("BSA-key-123", seenToken.get());
        assertNotNull(seenQuery.get());
        assertTrue(seenQuery.get().contains("freshness=pw"));
        assertTrue(seenQuery.get().contains("count=5"));
        assertEquals(2, results.size());
        assertEquals("Brave Result", results.get(0).title);
        assertEquals("Primary snippet", results.get(0).snippet);
        assertEquals("Fallback snippet", results.get(1).snippet);
    }

    @Test public void tavilyPostsJsonPayloadWithBearerAuth() throws Exception {
        AtomicReference<String> seenMethod = new AtomicReference<>();
        AtomicReference<String> seenAuth = new AtomicReference<>();
        AtomicReference<String> seenBody = new AtomicReference<>();
        server.createContext("/search", exchange -> {
            seenMethod.set(exchange.getRequestMethod());
            seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            seenBody.set(readAll(exchange.getRequestBody()));
            String json = "{\"results\":[{\"title\":\"Tavily Hit\",\"url\":\"https://tavily.example/hit\",\"content\":\"Clean LLM snippet\"}]}";
            reply(exchange, 200, json, null);
        });
        server.start();

        List<WebSearch.Source> results = WebSearch.searchTavily(
                baseUrl + "/search", "tvly-secret", "LFM3", "day", Locale.US);

        assertEquals("POST", seenMethod.get());
        assertEquals("Bearer tvly-secret", seenAuth.get());
        assertNotNull(seenBody.get());
        assertTrue(seenBody.get().contains("\"query\":\"LFM3\""));
        assertTrue(seenBody.get().contains("\"time_range\":\"day\""));
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
                    return java.util.Collections.singletonList(
                            new WebSearch.Source("Hanami", "Local assistant", "https://en.wikipedia.org/?curid=1"));
                });
        AndroidActionExecutor.ActionResult result = executor.execute("web_search",
                java.util.Collections.singletonMap("query", "Hanami"));
        assertTrue(result.succeeded);
        assertEquals("WEB_SEARCH_RESULT", result.status);
        assertEquals(SearchProvider.WIKIPEDIA, calledProvider.get());
        assertEquals("Hanami", calledQuery.get());
        assertTrue(result.message.contains("UNTRUSTED_WEB_SEARCH"));
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int code,
                              String body, String retryAfter) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (retryAfter != null) exchange.getResponseHeaders().set("Retry-After", retryAfter);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
