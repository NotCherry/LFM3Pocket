package com.notcherry.lfm3pocket;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SearchEndpointPolicyTest {
    @Test public void emptyOrHttpsEndpointsAreAccepted() {
        assertTrue(SearchEndpointPolicy.accepts(null));
        assertTrue(SearchEndpointPolicy.accepts(""));
        assertTrue(SearchEndpointPolicy.accepts("   "));
        assertTrue(SearchEndpointPolicy.accepts("https://searx.example/search"));
    }

    @Test public void cleartextAndNonHttpsEndpointsAreRejected() {
        assertFalse(SearchEndpointPolicy.accepts("http://searx.example/search"));
        assertFalse(SearchEndpointPolicy.accepts("ftp://searx.example/search"));
        assertFalse(SearchEndpointPolicy.accepts("searx.example/search"));
    }

    @Test public void searchStaysDisabledWithoutHttpsEndpoint() {
        assertFalse(SearchEndpointPolicy.enabled(true, ""));
        assertFalse(SearchEndpointPolicy.enabled(true, "http://searx.example/search"));
        assertFalse(SearchEndpointPolicy.enabled(false, "https://searx.example/search"));
        assertTrue(SearchEndpointPolicy.enabled(true, "https://searx.example/search"));
    }
}
