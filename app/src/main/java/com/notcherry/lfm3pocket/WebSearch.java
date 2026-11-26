package com.notcherry.lfm3pocket;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Opt-in web search client supporting SearXNG, Wikipedia, Brave Search API, and Tavily Search API.
 * Network access is only used when the user explicitly configures and enables web search.
 */
final class WebSearch {
    private static final Set<HttpURLConnection> ACTIVE_CONNECTIONS =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final int MAX_RESULTS = 5;
    private static final int MAX_RESPONSE_CHARS = 2_000_000;
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    static final String BRAVE_SEARCH_URL = "https://api.search.brave.com/res/v1/web/search";
    static final String TAVILY_SEARCH_URL = "https://api.tavily.com/search";

    static final class Source {
        final String title;
        final String snippet;
        final String url;
        Source(String title, String snippet, String url) {
            this.title = title;
            this.snippet = snippet;
            this.url = url;
        }
    }

    static final String USER_AGENT =
            "Hanami/1.0 (https://github.com/NotCherry/LFM3Pocket; Android on-device assistant)";

    private WebSearch() {}

    /** Best-effort abort for in-flight search sockets when the user taps Stop or Clear. */
    static void cancelActive() {
        for (HttpURLConnection connection : ACTIVE_CONNECTIONS) {
            try {
                connection.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Normalizes a user-entered SearXNG URL so bare hosts like {@code https://searx.example}
     * resolve to {@code https://searx.example/search}. Preserves explicit paths and query strings.
     */
    static String normalizeEndpoint(String endpoint) {
        if (endpoint == null) return "";
        String trimmed = endpoint.trim();
        if (trimmed.isEmpty()) return "";
        if (trimmed.contains("?")) return trimmed;
        int schemeIdx = trimmed.indexOf("://");
        if (schemeIdx < 0) return trimmed;
        int pathStart = trimmed.indexOf('/', schemeIdx + 3);
        if (pathStart < 0) return trimmed + "/search";
        String path = trimmed.substring(pathStart);
        if (path.equals("/")) return trimmed + "search";
        return trimmed;
    }

    static List<Source> search(String endpoint, String query) throws Exception {
        return search(endpoint, query, "");
    }

    static List<Source> search(String endpoint, String query, String timeRange) throws Exception {
        return search(SearchConfig.forSearxng(endpoint, true), query, timeRange, Locale.getDefault());
    }

    static List<Source> search(SearchConfig config, String query, String timeRange) throws Exception {
        return search(config, query, timeRange, Locale.getDefault());
    }

    static List<Source> search(SearchConfig config, String query, String timeRange, Locale locale) throws Exception {
        SearchConfig cfg = config == null ? new SearchConfig(SearchProvider.SEARXNG, "", "", false) : config;
        String validationError = SearchEndpointPolicy.validationError(cfg.provider, cfg.endpoint, cfg.apiKey);
        if (validationError != null) {
            throw new Exception(validationError);
        }
        String safeQuery = query == null ? "" : query.trim();
        String safeTimeRange = timeRange == null ? "" : timeRange.trim();
        Locale effectiveLocale = locale == null ? Locale.getDefault() : locale;
        switch (cfg.provider) {
            case SEARXNG:
                return searchSearxng(cfg.endpoint, cfg.apiKey, safeQuery, safeTimeRange, effectiveLocale);
            case WIKIPEDIA:
                return searchWikipedia(safeQuery, effectiveLocale);
            case BRAVE:
                return searchBrave(BRAVE_SEARCH_URL, cfg.apiKey, safeQuery, safeTimeRange, effectiveLocale);
            case TAVILY:
                return searchTavily(TAVILY_SEARCH_URL, cfg.apiKey, safeQuery, safeTimeRange, effectiveLocale);
            default:
                throw new Exception("Unsupported search provider.");
        }
    }

    static List<Source> searchSearxng(String endpoint, String bearerToken, String query,
                                      String timeRange, Locale locale) throws Exception {
        String normalized = normalizeEndpoint(endpoint);
        if (!SearchEndpointPolicy.accepts(normalized) || normalized.isEmpty()) {
            throw new Exception(SearchEndpointPolicy.SEARXNG_ENDPOINT_ERROR);
        }
        String separator = normalized.contains("?") ? "&" : "?";
        StringBuilder urlBuilder = new StringBuilder(normalized)
                .append(separator)
                .append("q=").append(URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8.name()))
                .append("&format=json");
        if (timeRange != null && !timeRange.trim().isEmpty()) {
            urlBuilder.append("&time_range=").append(
                    URLEncoder.encode(timeRange.trim(), StandardCharsets.UTF_8.name()));
        }
        String auth = bearerToken == null || bearerToken.trim().isEmpty()
                ? null : "Bearer " + bearerToken.trim();
        String raw = executeRequest(urlBuilder.toString(), "GET", null, auth, null,
                locale, SearchProvider.SEARXNG);
        return parseSearxngJson(raw);
    }

    static List<Source> searchWikipedia(String query, Locale locale) throws Exception {
        String lang = wikipediaLanguage(locale);
        String primaryUrl = "https://" + lang + ".wikipedia.org/w/api.php";
        List<Source> results = searchWikipediaAt(primaryUrl, lang, query, locale);
        if (results.isEmpty() && !"en".equals(lang)) {
            return searchWikipediaAt("https://en.wikipedia.org/w/api.php", "en", query, locale);
        }
        return results;
    }

    static List<Source> searchWikipediaAt(String apiUrl, String lang, String query, Locale locale) throws Exception {
        String separator = apiUrl.contains("?") ? "&" : "?";
        String url = apiUrl + separator
                + "action=query&list=search&srsearch="
                + URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8.name())
                + "&srlimit=" + MAX_RESULTS
                + "&utf8=1&format=json";
        String raw = executeRequest(url, "GET", null, null, null, locale, SearchProvider.WIKIPEDIA);
        return parseWikipediaJson(raw, lang);
    }

    static List<Source> searchBrave(String baseUrl, String apiKey, String query,
                                    String timeRange, Locale locale) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new Exception("Enter your Brave Search API key.");
        }
        String separator = baseUrl.contains("?") ? "&" : "?";
        StringBuilder urlBuilder = new StringBuilder(baseUrl)
                .append(separator)
                .append("q=").append(URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8.name()))
                .append("&count=").append(MAX_RESULTS);
        String freshness = mapBraveFreshness(timeRange);
        if (freshness != null) {
            urlBuilder.append("&freshness=").append(freshness);
        }
        String raw = executeRequest(urlBuilder.toString(), "GET", null, null, apiKey.trim(),
                locale, SearchProvider.BRAVE);
        return parseBraveJson(raw);
    }

    static List<Source> searchTavily(String baseUrl, String apiKey, String query,
                                     String timeRange, Locale locale) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new Exception("Enter your Tavily API key.");
        }
        JSONObject payload = new JSONObject();
        payload.put("query", query == null ? "" : query);
        payload.put("max_results", MAX_RESULTS);
        payload.put("search_depth", "basic");
        String normalizedRange = normalizeTavilyTimeRange(timeRange);
        if (normalizedRange != null) {
            payload.put("time_range", normalizedRange);
        }
        String raw = executeRequest(baseUrl, "POST", payload.toString(),
                "Bearer " + apiKey.trim(), null, locale, SearchProvider.TAVILY);
        return parseTavilyJson(raw);
    }

    private static String executeRequest(String requestUrl, String method, String jsonBody,
                                         String authorizationHeader, String braveTokenHeader,
                                         Locale locale, SearchProvider provider) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(requestUrl).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Language", buildAcceptLanguageHeader(locale));
        connection.setRequestProperty("User-Agent", USER_AGENT);
        if (authorizationHeader != null && !authorizationHeader.isEmpty()) {
            connection.setRequestProperty("Authorization", authorizationHeader);
        }
        if (braveTokenHeader != null && !braveTokenHeader.isEmpty()) {
            connection.setRequestProperty("X-Subscription-Token", braveTokenHeader);
        }
        ACTIVE_CONNECTIONS.add(connection);
        try {
            if (jsonBody != null) {
                byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(bodyBytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bodyBytes);
                }
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                String retryAfter = connection.getHeaderField("Retry-After");
                throw new Exception(formatHttpError(provider, code, retryAfter));
            }
            try (InputStream input = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                char[] buffer = new char[4096];
                int n;
                while ((n = reader.read(buffer)) != -1) {
                    if (body.length() + n > MAX_RESPONSE_CHARS) {
                        throw new Exception("Search response is too large");
                    }
                    body.append(buffer, 0, n);
                }
                String raw = body.toString().trim();
                if (raw.startsWith("<")) {
                    throw new Exception("Search endpoint returned HTML instead of JSON (check /search path and format=json)");
                }
                return raw;
            }
        } finally {
            ACTIVE_CONNECTIONS.remove(connection);
            connection.disconnect();
        }
    }

    static String formatHttpError(SearchProvider provider, int code, String retryAfter) {
        String retrySuffix = "";
        if (retryAfter != null && !retryAfter.trim().isEmpty()) {
            retrySuffix = "; Retry-After: " + retryAfter.trim();
        }
        SearchProvider active = provider == null ? SearchProvider.SEARXNG : provider;
        switch (active) {
            case SEARXNG:
                if (code == 401) {
                    return "SearXNG returned HTTP 401 (authentication required — add a Bearer token in Settings)";
                }
                if (code == 403) {
                    return "SearXNG returned HTTP 403 (JSON format disabled on this instance — enable formats: [html, json] or switch provider in Settings)";
                }
                if (code == 429) {
                    return "SearXNG returned HTTP 429 (rate limited" + retrySuffix
                            + "). Public SearXNG instances block JSON API clients — use a self-hosted instance (limiter: false or pass_ip) or switch to Wikipedia, Brave Search, or Tavily in Settings.";
                }
                return "SearXNG returned HTTP " + code;
            case BRAVE:
                if (code == 401 || code == 403) {
                    return "Brave Search returned HTTP " + code + " (check your Brave Search API key in Settings)";
                }
                if (code == 429) {
                    return "Brave Search returned HTTP 429 (rate limit or monthly quota reached" + retrySuffix + ")";
                }
                return "Brave Search returned HTTP " + code;
            case TAVILY:
                if (code == 401 || code == 403) {
                    return "Tavily returned HTTP " + code + " (check your Tavily API key in Settings)";
                }
                if (code == 429 || code == 432) {
                    return "Tavily returned HTTP " + code + " (rate limit or monthly quota reached" + retrySuffix + ")";
                }
                return "Tavily returned HTTP " + code;
            case WIKIPEDIA:
                if (code == 429) {
                    return "Wikipedia returned HTTP 429 (rate limited" + retrySuffix + ")";
                }
                return "Wikipedia returned HTTP " + code;
            default:
                return "Search returned HTTP " + code;
        }
    }

    static List<Source> parseSearxngJson(String raw) throws Exception {
        JSONArray results = new JSONObject(raw).optJSONArray("results");
        ArrayList<Source> sources = new ArrayList<>();
        if (results == null) return sources;
        for (int i = 0; i < results.length() && sources.size() < MAX_RESULTS; i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null) continue;
            String resultUrl = item.optString("url", "");
            if (!isHttpUrl(resultUrl)) continue;
            sources.add(new Source(
                    item.optString("title", "Web result"),
                    item.optString("content", ""),
                    resultUrl));
        }
        return sources;
    }

    static List<Source> parseWikipediaJson(String raw, String lang) throws Exception {
        String safeLang = sanitizeWikiLang(lang);
        JSONObject root = new JSONObject(raw);
        JSONObject queryObj = root.optJSONObject("query");
        JSONArray results = queryObj == null ? null : queryObj.optJSONArray("search");
        ArrayList<Source> sources = new ArrayList<>();
        if (results == null) return sources;
        for (int i = 0; i < results.length() && sources.size() < MAX_RESULTS; i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null) continue;
            String title = item.optString("title", "").trim();
            if (title.isEmpty()) continue;
            long pageId = item.optLong("pageid", 0L);
            String resultUrl = pageId > 0L
                    ? "https://" + safeLang + ".wikipedia.org/?curid=" + pageId
                    : "https://" + safeLang + ".wikipedia.org/wiki/"
                            + URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8.name());
            String snippet = stripHtml(item.optString("snippet", ""));
            sources.add(new Source(title, snippet, resultUrl));
        }
        return sources;
    }

    static List<Source> parseBraveJson(String raw) throws Exception {
        JSONObject root = new JSONObject(raw);
        JSONObject web = root.optJSONObject("web");
        JSONArray results = web == null ? null : web.optJSONArray("results");
        ArrayList<Source> sources = new ArrayList<>();
        if (results == null) return sources;
        for (int i = 0; i < results.length() && sources.size() < MAX_RESULTS; i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null) continue;
            String resultUrl = item.optString("url", "");
            if (!isHttpUrl(resultUrl)) continue;
            String snippet = stripHtml(item.optString("description", ""));
            if (snippet.isEmpty()) {
                JSONArray extras = item.optJSONArray("extra_snippets");
                if (extras != null && extras.length() > 0) {
                    snippet = stripHtml(extras.optString(0, ""));
                }
            }
            sources.add(new Source(
                    stripHtml(item.optString("title", "Web result")),
                    snippet,
                    resultUrl));
        }
        return sources;
    }

    static List<Source> parseTavilyJson(String raw) throws Exception {
        JSONArray results = new JSONObject(raw).optJSONArray("results");
        ArrayList<Source> sources = new ArrayList<>();
        if (results == null) return sources;
        for (int i = 0; i < results.length() && sources.size() < MAX_RESULTS; i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null) continue;
            String resultUrl = item.optString("url", "");
            if (!isHttpUrl(resultUrl)) continue;
            sources.add(new Source(
                    item.optString("title", "Web result"),
                    item.optString("content", ""),
                    resultUrl));
        }
        return sources;
    }

    static String buildAcceptLanguageHeader(Locale locale) {
        Locale effective = locale == null ? Locale.US : locale;
        String lang = effective.getLanguage();
        if (lang == null || lang.trim().isEmpty()) {
            return "en-US,en;q=0.9";
        }
        String normLang = lang.trim().toLowerCase(Locale.ROOT);
        String country = effective.getCountry() == null ? "" : effective.getCountry().trim().toUpperCase(Locale.ROOT);
        StringBuilder header = new StringBuilder();
        if (!country.isEmpty()) {
            header.append(normLang).append('-').append(country).append(',').append(normLang).append(";q=0.9");
        } else {
            header.append(normLang);
        }
        if (!"en".equals(normLang)) {
            header.append(",en;q=0.5");
        }
        return header.toString();
    }

    static String wikipediaLanguage(Locale locale) {
        if (locale == null) return "en";
        return sanitizeWikiLang(locale.getLanguage());
    }

    private static String sanitizeWikiLang(String raw) {
        if (raw == null) return "en";
        String lang = raw.trim().toLowerCase(Locale.ROOT);
        if (lang.length() < 2 || lang.length() > 3) return "en";
        for (int i = 0; i < lang.length(); i++) {
            char c = lang.charAt(i);
            if (c < 'a' || c > 'z') return "en";
        }
        return lang;
    }

    static String mapBraveFreshness(String timeRange) {
        if (timeRange == null) return null;
        switch (timeRange.trim().toLowerCase(Locale.ROOT)) {
            case "day":
            case "pd":
                return "pd";
            case "week":
            case "pw":
                return "pw";
            case "month":
            case "pm":
                return "pm";
            case "year":
            case "py":
                return "py";
            default:
                return null;
        }
    }

    static String normalizeTavilyTimeRange(String timeRange) {
        if (timeRange == null) return null;
        switch (timeRange.trim().toLowerCase(Locale.ROOT)) {
            case "day":
            case "week":
            case "month":
            case "year":
                return timeRange.trim().toLowerCase(Locale.ROOT);
            default:
                return null;
        }
    }

    static String stripHtml(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String noTags = HTML_TAG_PATTERN.matcher(raw).replaceAll("");
        String unescaped = noTags
                .replace("&quot;", "\"")
                .replace("&#039;", "'")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&");
        return WHITESPACE_PATTERN.matcher(unescaped).replaceAll(" ").trim();
    }

    private static boolean isHttpUrl(String url) {
        return url != null && (url.startsWith("https://") || url.startsWith("http://"));
    }
}
