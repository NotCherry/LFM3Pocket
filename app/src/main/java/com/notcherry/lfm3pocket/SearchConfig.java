package com.notcherry.lfm3pocket;

import android.content.SharedPreferences;

/** Immutable snapshot of the user's opt-in web search configuration. */
final class SearchConfig {
    static final String PREF_PROVIDER = "search_provider";
    static final String PREF_ENDPOINT = "search_endpoint";
    static final String PREF_API_KEY = "search_api_key";
    static final String PREF_ENABLED = "search_enabled";

    final SearchProvider provider;
    final String endpoint;
    final String apiKey;
    final boolean enabled;

    SearchConfig(SearchProvider provider, String endpoint, String apiKey, boolean enabled) {
        this.provider = provider == null ? SearchProvider.SEARXNG : provider;
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.enabled = enabled;
    }

    static SearchConfig forSearxng(String endpoint, boolean enabled) {
        return new SearchConfig(SearchProvider.SEARXNG, endpoint, "", enabled);
    }

    static String apiKeyPrefFor(SearchProvider provider) {
        SearchProvider p = provider == null ? SearchProvider.SEARXNG : provider;
        return PREF_API_KEY + "_" + p.wireName;
    }

    static String readApiKey(SharedPreferences prefs, SearchProvider provider) {
        if (prefs == null) return "";
        SearchProvider p = provider == null ? SearchProvider.SEARXNG : provider;
        if (!p.needsApiKey && !p.supportsOptionalToken) return "";
        String scoped = prefs.getString(apiKeyPrefFor(p), null);
        if (scoped != null) return scoped.trim();
        SearchProvider active = SearchProvider.fromWire(prefs.getString(PREF_PROVIDER, SearchProvider.SEARXNG.wireName));
        if (active == p) {
            String legacy = prefs.getString(PREF_API_KEY, "");
            return legacy == null ? "" : legacy.trim();
        }
        return "";
    }

    static SearchConfig fromPreferences(SharedPreferences prefs) {
        if (prefs == null) return new SearchConfig(SearchProvider.SEARXNG, "", "", false);
        SearchProvider provider = SearchProvider.fromWire(
                prefs.getString(PREF_PROVIDER, SearchProvider.SEARXNG.wireName));
        String endpoint = prefs.getString(PREF_ENDPOINT, "");
        String apiKey = readApiKey(prefs, provider);
        boolean enabled = prefs.getBoolean(PREF_ENABLED, false);
        return new SearchConfig(provider, endpoint, apiKey, enabled);
    }

    static void saveToPreferences(SharedPreferences prefs, SearchConfig config) {
        if (prefs == null || config == null) return;
        SharedPreferences.Editor editor = prefs.edit()
                .putString(PREF_PROVIDER, config.provider.wireName)
                .putString(PREF_ENDPOINT, config.endpoint)
                .putBoolean(PREF_ENABLED, config.enabled);
        if (config.provider.needsApiKey || config.provider.supportsOptionalToken) {
            editor.putString(PREF_API_KEY, config.apiKey)
                    .putString(apiKeyPrefFor(config.provider), config.apiKey);
        }
        editor.apply();
    }

    boolean isConfigured() {
        return SearchEndpointPolicy.configured(provider, endpoint, apiKey);
    }

    boolean isReady() {
        return SearchEndpointPolicy.enabled(enabled, provider, endpoint, apiKey);
    }
}
