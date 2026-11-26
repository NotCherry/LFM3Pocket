package com.notcherry.lfm3pocket;

import java.util.Locale;

/**
 * Opt-in web search backends supported by Hanami.
 * Defaults to {@link #SEARXNG} to preserve existing installations and E2E contracts.
 */
public enum SearchProvider {
    SEARXNG("searxng", "SearXNG", true, false, true),
    WIKIPEDIA("wikipedia", "Wikipedia", false, false, false),
    BRAVE("brave", "Brave Search", false, true, false),
    TAVILY("tavily", "Tavily", false, true, false);

    public final String wireName;
    public final String displayName;
    public final boolean needsEndpoint;
    public final boolean needsApiKey;
    public final boolean supportsOptionalToken;

    SearchProvider(String wireName, String displayName,
                   boolean needsEndpoint, boolean needsApiKey, boolean supportsOptionalToken) {
        this.wireName = wireName;
        this.displayName = displayName;
        this.needsEndpoint = needsEndpoint;
        this.needsApiKey = needsApiKey;
        this.supportsOptionalToken = supportsOptionalToken;
    }

    public static SearchProvider fromWire(String raw) {
        if (raw == null) return SEARXNG;
        String norm = raw.trim().toLowerCase(Locale.ROOT);
        for (SearchProvider value : values()) {
            if (value.wireName.equals(norm)) return value;
        }
        return SEARXNG;
    }
}
