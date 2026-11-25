package com.notcherry.lfm3pocket;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Small SearXNG JSON client. Network access is only used when the user enables search. */
final class WebSearch {
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

    static final String USER_AGENT = "Hanami/1.0 (Android; on-device assistant)";

    private WebSearch() {}

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
        String normalized = normalizeEndpoint(endpoint);
        if (!SearchEndpointPolicy.accepts(normalized) || normalized.isEmpty()) {
            throw new Exception("Configure a valid HTTPS SearXNG endpoint in Settings.");
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
        HttpURLConnection connection = (HttpURLConnection) new URL(urlBuilder.toString()).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        try {
            int code = connection.getResponseCode();
            if (code == 403) {
                throw new Exception("SearXNG returned HTTP 403 (ensure JSON format is enabled on this instance)");
            }
            if (code == 429) {
                throw new Exception("SearXNG returned HTTP 429 (rate limited)");
            }
            if (code < 200 || code >= 300) {
                throw new Exception("Search returned HTTP " + code);
            }
            try (InputStream input = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                char[] buffer = new char[4096];
                int n;
                while ((n = reader.read(buffer)) != -1) {
                    if (body.length() + n > 2_000_000) throw new Exception("Search response is too large");
                    body.append(buffer, 0, n);
                }
                String raw = body.toString().trim();
                if (raw.startsWith("<")) {
                    throw new Exception("Search endpoint returned HTML instead of JSON (check /search path and format=json)");
                }
                JSONArray results = new JSONObject(raw).optJSONArray("results");
                ArrayList<Source> sources = new ArrayList<>();
                if (results == null) return sources;
                for (int i = 0; i < results.length() && sources.size() < 5; i++) {
                    JSONObject item = results.optJSONObject(i);
                    if (item == null) continue;
                    String resultUrl = item.optString("url", "");
                    if (!resultUrl.startsWith("https://") && !resultUrl.startsWith("http://")) continue;
                    sources.add(new Source(item.optString("title", "Web result"),
                            item.optString("content", ""), resultUrl));
                }
                return sources;
            }
        } finally {
            connection.disconnect();
        }
    }
}
