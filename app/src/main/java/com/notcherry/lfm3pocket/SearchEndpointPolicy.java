package com.notcherry.lfm3pocket;

/** HTTPS-only opt-in gate for the optional SearXNG endpoint. */
final class SearchEndpointPolicy {
    private SearchEndpointPolicy() {}

    static boolean accepts(String endpoint) {
        if (endpoint == null) return true;
        String value = endpoint.trim();
        return value.isEmpty() || value.startsWith("https://");
    }

    static boolean enabled(boolean requested, String endpoint) {
        if (!requested || endpoint == null) return false;
        String value = endpoint.trim();
        return !value.isEmpty() && accepts(value);
    }
}
