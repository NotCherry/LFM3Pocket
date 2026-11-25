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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Small SearXNG JSON client. Network access is only used when the user enables search. */
final class WebSearch {
    private static final Set<HttpURLConnection> ACTIVE_CONNECTIONS =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

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

    static List<Source> search(String endpoint, String query) throws Exception {
        String separator = endpoint.contains("?") ? "&" : "?";
        String requestUrl = endpoint + separator + "q=" +
                URLEncoder.encode(query, StandardCharsets.UTF_8.name()) + "&format=json";
        HttpURLConnection connection = (HttpURLConnection) new URL(requestUrl).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/json");
        ACTIVE_CONNECTIONS.add(connection);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("Search returned HTTP " + code);
            try (InputStream input = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                char[] buffer = new char[4096];
                int n;
                while ((n = reader.read(buffer)) != -1) {
                    if (body.length() + n > 2_000_000) throw new Exception("Search response is too large");
                    body.append(buffer, 0, n);
                }
                JSONArray results = new JSONObject(body.toString()).optJSONArray("results");
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
            ACTIVE_CONNECTIONS.remove(connection);
            connection.disconnect();
        }
    }
}

