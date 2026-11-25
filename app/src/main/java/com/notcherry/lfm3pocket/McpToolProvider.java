package com.notcherry.lfm3pocket;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Manages configured MCP servers, caches discovered tools for offline prompt/grammar loading,
 * and implements {@link ExternalToolProvider} so MCP tools work seamlessly via {@code search_tools}
 * and {@link ToolCoordinator} user confirmation.
 */
public final class McpToolProvider implements ExternalToolProvider {
    public interface Caller {
        List<McpServerConfig.ToolDef> listTools(McpServerConfig server) throws IOException;
        AndroidActionExecutor.ActionResult callTool(
                McpServerConfig server, String remoteToolName, Map<String, Object> arguments) throws IOException;
    }

    public static final Caller DEFAULT_CALLER = new Caller() {
        @Override public List<McpServerConfig.ToolDef> listTools(McpServerConfig server) throws IOException {
            return McpClient.listTools(server);
        }
        @Override public AndroidActionExecutor.ActionResult callTool(
                McpServerConfig server, String remoteToolName, Map<String, Object> arguments) throws IOException {
            return McpClient.callTool(server, remoteToolName, arguments);
        }
    };

    private static final class Resolved {
        final McpServerConfig server;
        final McpServerConfig.ToolDef tool;

        Resolved(McpServerConfig server, McpServerConfig.ToolDef tool) {
            this.server = server;
            this.tool = tool;
        }
    }

    private final Supplier<String> readJson;
    private final Consumer<String> writeJson;
    private final Caller caller;

    public McpToolProvider(Supplier<String> readJson, Consumer<String> writeJson) {
        this(readJson, writeJson, DEFAULT_CALLER);
    }

    public McpToolProvider(Supplier<String> readJson, Consumer<String> writeJson, Caller caller) {
        if (readJson == null || writeJson == null || caller == null) {
            throw new IllegalArgumentException("Missing MCP provider dependencies");
        }
        this.readJson = readJson;
        this.writeJson = writeJson;
        this.caller = caller;
    }

    public List<McpServerConfig> listServers() {
        return McpServerConfig.deserializeList(readJson.get());
    }

    public void addOrReplace(McpServerConfig config) {
        if (config == null) throw new IllegalArgumentException("config is required");
        List<McpServerConfig> current = new ArrayList<>(listServers());
        current.removeIf(s -> s.id.equals(config.id));
        if (current.size() >= 16) {
            throw new IllegalStateException("Maximum of 16 MCP servers reached");
        }
        current.add(config);
        writeJson.accept(McpServerConfig.serializeList(current));
    }

    public boolean remove(String serverId) {
        List<McpServerConfig> current = new ArrayList<>(listServers());
        boolean removed = current.removeIf(s -> s.id.equals(serverId));
        if (removed) writeJson.accept(McpServerConfig.serializeList(current));
        return removed;
    }

    /** Queries {@code tools/list} on one configured server and persists the discovered tools. */
    public List<McpServerConfig.ToolDef> syncServer(String serverId) throws IOException {
        List<McpServerConfig> current = new ArrayList<>(listServers());
        for (int i = 0; i < current.size(); i++) {
            McpServerConfig s = current.get(i);
            if (s.id.equals(serverId)) {
                List<McpServerConfig.ToolDef> discovered = caller.listTools(s);
                current.set(i, s.withCachedTools(discovered));
                writeJson.accept(McpServerConfig.serializeList(current));
                return discovered;
            }
        }
        throw new IOException("MCP server not found: " + serverId);
    }

    private Resolved resolve(String wireName) {
        if (wireName == null || !wireName.startsWith("mcp.")) return null;
        for (McpServerConfig s : listServers()) {
            if (!s.enabled) continue;
            for (McpServerConfig.ToolDef def : s.cachedTools) {
                if (wireName.equals(s.wireNameFor(def.name))) {
                    return new Resolved(s, def);
                }
            }
        }
        return null;
    }

    @Override public String providerId() { return "mcp"; }

    @Override public List<LfmToolCatalog.ToolSpec> discoverTools() {
        List<LfmToolCatalog.ToolSpec> out = new ArrayList<>();
        for (McpServerConfig s : listServers()) {
            if (!s.enabled) continue;
            for (McpServerConfig.ToolDef def : s.cachedTools) {
                out.add(s.toToolSpec(def));
            }
        }
        return out;
    }

    @Override public boolean supportsTool(String toolWireName) {
        return resolve(toolWireName) != null;
    }

    @Override public String formatForDisplay(String toolWireName, Map<String, Object> arguments) {
        Resolved resolved = resolve(toolWireName);
        StringBuilder out = new StringBuilder();
        if (resolved == null) {
            out.append("MCP tool\nName: ").append(toolWireName);
        } else {
            out.append("MCP tool: ").append(resolved.tool.name);
            out.append("\nServer: ").append(resolved.server.label);
            out.append("\nEndpoint: ").append(resolved.server.url);
        }
        if (arguments != null && !arguments.isEmpty()) {
            for (Map.Entry<String, Object> e : arguments.entrySet()) {
                String val = String.valueOf(e.getValue());
                if (val.length() > 160) val = val.substring(0, 157) + "…";
                out.append('\n').append(e.getKey()).append(": ").append(val);
            }
        }
        return out.toString();
    }

    @Override public AndroidActionExecutor.ActionResult execute(String toolWireName, Map<String, Object> arguments) {
        Resolved resolved = resolve(toolWireName);
        if (resolved == null) {
            return new AndroidActionExecutor.ActionResult(false, "MCP_TOOL_UNAVAILABLE",
                    "That MCP server or tool is disabled or no longer synced.");
        }
        try {
            return caller.callTool(resolved.server, resolved.tool.name, arguments);
        } catch (IOException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new AndroidActionExecutor.ActionResult(false, "MCP_CALL_FAILED",
                    "MCP call failed (" + resolved.server.label + "): " + msg);
        }
    }
}
