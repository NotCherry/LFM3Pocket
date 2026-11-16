package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

public final class LoopbackAgentApiTest {
    @Test public void askSubmitsPromptAndApproveIsAlwaysForbidden() {
        AtomicReference<String> seen = new AtomicReference<>();
        LoopbackAgentApi.PromptHandler handler = prompt -> {
            seen.set(prompt);
            return true;
        };
        String ask = LoopbackAgentApi.handle("POST", "/v1/ask", "{\"prompt\":\"tide height\"}", handler);
        assertTrue(ask.startsWith("HTTP/1.1 202"));
        assertTrue("tide height".equals(seen.get()));

        String approve = LoopbackAgentApi.handle("POST", "/v1/approve",
                "{\"proposal_id\":\"anything\"}", handler);
        assertTrue(approve.startsWith("HTTP/1.1 403"));
        assertTrue(approve.contains("cannot approve"));
        assertFalse(approve.contains("202"));
    }

    @Test public void blankPromptIsRejected() {
        String response = LoopbackAgentApi.handle("POST", "/v1/ask", "{\"prompt\":\"\"}", prompt -> true);
        assertTrue(response.startsWith("HTTP/1.1 400"));
    }
}
