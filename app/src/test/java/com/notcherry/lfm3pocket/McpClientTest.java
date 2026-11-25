package com.notcherry.lfm3pocket;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Unit tests for Sprint 3 Open MCP Streamable HTTP Client, Endpoint Policy, and Tool Provider. */
public final class McpClientTest {
    @Test public void endpointPolicyAllowsLoopbackOfflineAndRequiresHttpsForPublicHosts() {
        assertTrue(McpEndpointPolicy.isAllowed("http://127.0.0.1:3000/mcp", false));
        assertTrue(McpEndpointPolicy.isAllowed("http://localhost:8080/mcp", false));
        assertFalse("LAN HTTP requires explicit opt-in",
                McpEndpointPolicy.isAllowed("http://192.168.1.50:3000/mcp", false));
        assertTrue("LAN HTTP works when opted-in",
                McpEndpointPolicy.isAllowed("http://192.168.1.50:3000/mcp", true));
        assertTrue(McpEndpointPolicy.isAllowed("http://10.0.0.5:3000/mcp", true));
        assertTrue(McpEndpointPolicy.isAllowed("http://homelab.local:3000/mcp", true));
        assertFalse("Public HTTP must ALWAYS be rejected",
                McpEndpointPolicy.isAllowed("http://mcp.composio.dev/mcp", true));
        assertTrue("Public HTTPS (Composio, GitHub, etc.) is allowed",
                McpEndpointPolicy.isAllowed("https://mcp.composio.dev/mcp", false));
    }

    @Test public void mcpClientEndToEndInitializeListAndCallOverLoopbackHttpAndSse() throws Exception {
        AtomicReference<String> seenAuth = new AtomicReference<>("");
        AtomicReference<String> seenSessionOnCall = new AtomicReference<>("");

        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();
            Thread thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        handleMockMcpRequest(socket, seenAuth, seenSessionOnCall);
                    } catch (IOException ignored) {
                        break;
                    }
                }
            }, "mock-mcp-server");
            thread.setDaemon(true);
            thread.start();

            String[] storedJson = { "[]" };
            McpToolProvider provider = new McpToolProvider(
                    () -> storedJson[0],
                    json -> storedJson[0] = json);
            provider.addOrReplace(new McpServerConfig(
                    "termux", "Termux Local",
                    "http://127.0.0.1:" + port + "/mcp",
                    "secret-token-123", true, false, null));

            // 1. Sync tools via initialize + tools/list (SSE response)
            List<McpServerConfig.ToolDef> tools = provider.syncServer("termux");
            assertEquals(1, tools.size());
            assertEquals("git_status", tools.get(0).name);
            assertEquals(Collections.singletonList("repo"), tools.get(0).parameterKeys);
            assertEquals("Bearer secret-token-123", seenAuth.get());

            // 2. Verify deferred discovery + grammar generation
            LfmToolCatalog.Capabilities caps = new LfmToolCatalog.Capabilities(
                    false, false, false, false, "", false, false, "", provider.discoverTools());
            LfmToolCatalog.SearchResult sr = LfmToolCatalog.searchTools("git", caps, 5);
            assertTrue(sr.formattedToolResponse.contains("\"name\":\"mcp.termux.git_status\""));
            assertTrue(LfmToolGrammar.build(sr.matched).contains("<function=mcp.termux.git_status>"));
            assertTrue(LfmToolGrammar.build(sr.matched).contains("<parameter=repo>"));

            // 3. Validate via ToolRegistry + confirm via ToolCoordinator + execute tools/call
            ToolRegistry registry = new ToolRegistry(() -> 1000L, Collections.singletonList(provider));
            ToolRegistry.Parsed parsed = registry.parse(
                    "{\"tool\":\"mcp.termux.git_status\",\"arguments\":{\"repo\":\"notes\"}}");
            assertTrue(parsed.message, parsed.isValid());
            String preview = ToolCoordinator.formatForDisplay(parsed.request, "");
            assertTrue(preview.contains("MCP tool: git_status"));
            assertTrue(preview.contains("Server: Termux Local"));
            assertTrue(preview.contains("repo: notes"));

            ToolCoordinator coordinator = new ToolCoordinator(
                    req -> {
                        ToolRegistry.ExternalToolRequest ext = (ToolRegistry.ExternalToolRequest) req;
                        return provider.execute(ext.externalWireName, ext.arguments);
                    },
                    req -> true, () -> 1000L);
            ToolCoordinator.Outcome pending = coordinator.propose(parsed.request, ToolCoordinator.Source.MODEL_PROPOSAL);
            assertEquals("PENDING_CONFIRMATION", pending.status);
            ToolCoordinator.Outcome done = coordinator.approve(pending.proposal.id);
            assertTrue(done.result.message, done.result.succeeded);
            assertEquals("sess-42", seenSessionOnCall.get());
            assertTrue(done.result.message.startsWith("UNTRUSTED_MCP"));
            assertTrue(done.result.message.contains("On branch main (repo=notes)"));
        }
    }

    private static void handleMockMcpRequest(Socket socket, AtomicReference<String> seenAuth,
                                             AtomicReference<String> seenSessionOnCall) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String requestLine = reader.readLine();
        if (requestLine == null) return;
        int contentLength = 0;
        String sessionHeader = "";
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String k = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String v = line.substring(colon + 1).trim();
                if ("content-length".equals(k)) contentLength = Integer.parseInt(v);
                else if ("authorization".equals(k)) seenAuth.set(v);
                else if ("mcp-session-id".equals(k)) sessionHeader = v;
            }
        }
        char[] bodyChars = new char[contentLength];
        int read = 0;
        while (read < contentLength) {
            int n = reader.read(bodyChars, read, contentLength - read);
            if (n < 0) break;
            read += n;
        }
        String body = new String(bodyChars, 0, read);

        String contentType = "application/json";
        String responseBody;
        if (body.contains("\"method\":\"initialize\"")) {
            responseBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"mock\"}}}";
        } else if (body.contains("\"method\":\"notifications/initialized\"")) {
            responseBody = "";
        } else if (body.contains("\"method\":\"tools/list\"")) {
            contentType = "text/event-stream";
            responseBody = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":["
                    + "{\"name\":\"git_status\",\"description\":\"Check local git repository status\","
                    + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"repo\":{\"type\":\"string\"}},\"required\":[\"repo\"]}}"
                    + "]}}\n\n";
        } else if (body.contains("\"method\":\"tools/call\"")) {
            seenSessionOnCall.set(sessionHeader);
            responseBody = "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"On branch main (repo=notes)\"}],\"isError\":false}}";
        } else {
            responseBody = "{\"jsonrpc\":\"2.0\",\"id\":0,\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}";
        }

        byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Mcp-Session-Id: sess-42\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();
    }
}
