package com.notcherry.lfm3pocket;

import java.net.URI;
import java.util.Locale;

/**
 * Opt-in endpoint and credential policy for web search providers.
 *
 * <p>For SearXNG, public hosts strictly require {@code https://}, while local loopback
 * ({@code http://127.0.0.1}, {@code http://localhost}, {@code http://[::1]}) is allowed
 * for self-hosted Termux / tunnelled instances (matching {@code network_security_config.xml}).
 */
final class SearchEndpointPolicy {
    static final String SEARXNG_ENDPOINT_ERROR =
            "Use an HTTPS SearXNG search endpoint (or http://127.0.0.1 for local Termux).";

    private SearchEndpointPolicy() {}

    static boolean accepts(String endpoint) {
        if (endpoint == null) return true;
        String value = endpoint.trim();
        if (value.isEmpty()) return true;
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) return false;
        if ("https".equals(scheme)) return true;
        return "http".equals(scheme) && McpEndpointPolicy.isLoopback(host);
    }

    static boolean enabled(boolean requested, String endpoint) {
        return enabled(requested, SearchProvider.SEARXNG, endpoint, "");
    }

    static boolean configured(SearchProvider provider, String endpoint, String apiKey) {
        return validationError(provider, endpoint, apiKey) == null;
    }

    static boolean enabled(boolean requested, SearchProvider provider, String endpoint, String apiKey) {
        return requested && configured(provider, endpoint, apiKey);
    }

    static String validationError(SearchProvider provider, String endpoint, String apiKey) {
        SearchProvider active = provider == null ? SearchProvider.SEARXNG : provider;
        switch (active) {
            case SEARXNG: {
                String value = endpoint == null ? "" : endpoint.trim();
                if (value.isEmpty() || !accepts(value)) return SEARXNG_ENDPOINT_ERROR;
                return null;
            }
            case WIKIPEDIA:
                return null;
            case BRAVE: {
                String key = apiKey == null ? "" : apiKey.trim();
                return key.isEmpty() ? "Enter your Brave Search API key." : null;
            }
            case TAVILY: {
                String key = apiKey == null ? "" : apiKey.trim();
                return key.isEmpty() ? "Enter your Tavily API key." : null;
            }
            default:
                return SEARXNG_ENDPOINT_ERROR;
        }
    }
}
