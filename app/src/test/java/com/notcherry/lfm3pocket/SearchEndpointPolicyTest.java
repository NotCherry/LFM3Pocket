package com.notcherry.lfm3pocket;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class SearchEndpointPolicyTest {
    @Test public void emptyOrHttpsEndpointsAreAccepted() {
        assertTrue(SearchEndpointPolicy.accepts(null));
        assertTrue(SearchEndpointPolicy.accepts(""));
        assertTrue(SearchEndpointPolicy.accepts("   "));
        assertTrue(SearchEndpointPolicy.accepts("https://searx.example/search"));
    }

    @Test public void loopbackHttpIsAcceptedForLocalSelfHostedSearxng() {
        assertTrue(SearchEndpointPolicy.accepts("http://127.0.0.1:8080/search"));
        assertTrue(SearchEndpointPolicy.accepts("http://localhost:8080/search"));
        assertTrue(SearchEndpointPolicy.accepts("http://[::1]:8080/search"));
        assertTrue(SearchEndpointPolicy.enabled(true, "http://127.0.0.1:8080/search"));
    }

    @Test public void cleartextAndNonHttpsEndpointsAreRejected() {
        assertFalse(SearchEndpointPolicy.accepts("http://searx.example/search"));
        assertFalse(SearchEndpointPolicy.accepts("http://192.168.1.10:8080/search"));
        assertFalse(SearchEndpointPolicy.accepts("ftp://searx.example/search"));
        assertFalse(SearchEndpointPolicy.accepts("searx.example/search"));
        assertFalse(SearchEndpointPolicy.accepts("https://"));
    }

    @Test public void searchStaysDisabledWithoutHttpsEndpoint() {
        assertFalse(SearchEndpointPolicy.enabled(true, ""));
        assertFalse(SearchEndpointPolicy.enabled(true, "http://searx.example/search"));
        assertFalse(SearchEndpointPolicy.enabled(false, "https://searx.example/search"));
        assertTrue(SearchEndpointPolicy.enabled(true, "https://searx.example/search"));
    }

    @Test public void multiProviderValidationRules() {
        // Wikipedia requires neither endpoint nor API key
        assertNull(SearchEndpointPolicy.validationError(SearchProvider.WIKIPEDIA, "", ""));
        assertTrue(SearchEndpointPolicy.enabled(true, SearchProvider.WIKIPEDIA, "", ""));
        assertFalse(SearchEndpointPolicy.enabled(false, SearchProvider.WIKIPEDIA, "", ""));

        // Brave requires an API key
        assertNotNull(SearchEndpointPolicy.validationError(SearchProvider.BRAVE, "", ""));
        assertFalse(SearchEndpointPolicy.enabled(true, SearchProvider.BRAVE, "", ""));
        assertNull(SearchEndpointPolicy.validationError(SearchProvider.BRAVE, "", "BSA-test-key"));
        assertTrue(SearchEndpointPolicy.enabled(true, SearchProvider.BRAVE, "", "BSA-test-key"));

        // Tavily requires an API key
        assertNotNull(SearchEndpointPolicy.validationError(SearchProvider.TAVILY, "", ""));
        assertFalse(SearchEndpointPolicy.enabled(true, SearchProvider.TAVILY, "", ""));
        assertNull(SearchEndpointPolicy.validationError(SearchProvider.TAVILY, "", "tvly-test-key"));
        assertTrue(SearchEndpointPolicy.enabled(true, SearchProvider.TAVILY, "", "tvly-test-key"));

        // SearXNG error message retains E2E contract substring
        String err = SearchEndpointPolicy.validationError(SearchProvider.SEARXNG, "http://searx.example/search", "");
        assertNotNull(err);
        assertTrue(err.contains("Use an HTTPS SearXNG search endpoint"));
        assertEquals(SearchProvider.SEARXNG, SearchProvider.fromWire(null));
        assertEquals(SearchProvider.SEARXNG, SearchProvider.fromWire("unknown"));
        assertEquals(SearchProvider.WIKIPEDIA, SearchProvider.fromWire("wikipedia"));
        assertEquals(SearchProvider.BRAVE, SearchProvider.fromWire("brave"));
        assertEquals(SearchProvider.TAVILY, SearchProvider.fromWire("tavily"));
    }
}
