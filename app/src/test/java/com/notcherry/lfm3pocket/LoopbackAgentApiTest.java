package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
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
        assertEquals("tide height", seen.get());

        String approve = LoopbackAgentApi.handle("POST", "/v1/approve",
                "{\"proposal_id\":\"anything\"}", handler);
        assertTrue(approve.startsWith("HTTP/1.1 403"));
        assertTrue(approve.contains("cannot approve"));
        assertFalse(approve.contains("202"));
    }

    @Test public void jsonPromptUsesRealJsonEscapingAndPreservesUnicode() {
        AtomicReference<String> seen = new AtomicReference<>();
        String json = "{\"prompt\":\"line\\nquote: \\\"x\\\" — żółć\"}";
        String response = LoopbackAgentApi.handle("POST", "/v1/ask", json, prompt -> {
            seen.set(prompt);
            return true;
        });
        assertTrue(response.startsWith("HTTP/1.1 202"));
        assertEquals("line\nquote: \"x\" — żółć", seen.get());
    }

    @Test public void malformedJsonAndNonStringPromptAreRejected() {
        assertTrue(LoopbackAgentApi.handle("POST", "/v1/ask", "{\"prompt\":", prompt -> true)
                .startsWith("HTTP/1.1 400"));
        assertTrue(LoopbackAgentApi.handle("POST", "/v1/ask", "{\"prompt\":42}", prompt -> true)
                .startsWith("HTTP/1.1 400"));
    }

    @Test public void blankPromptIsRejected() {
        String response = LoopbackAgentApi.handle("POST", "/v1/ask", "{\"prompt\":\"\"}", prompt -> true);
        assertTrue(response.startsWith("HTTP/1.1 400"));
    }

    @Test public void readRequestRejectsBareHeaderTerminator() throws Exception {
        byte[] bytes = "\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        assertEquals(null, LoopbackAgentApi.readRequest(new java.io.ByteArrayInputStream(bytes)));
    }
}
