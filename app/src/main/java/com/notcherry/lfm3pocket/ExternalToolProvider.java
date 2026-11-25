package com.notcherry.lfm3pocket;

import java.util.List;
import java.util.Map;

/**
 * Unified extension point for dynamically discovered external tools:
 * <ul>
 *   <li>Offline custom local URI/Intent shortcuts ({@link CustomShortcutStore})</li>
 *   <li>Open MCP Streamable HTTP servers — local Termux, LAN, or remote HTTPS ({@link McpToolProvider})</li>
 *   <li>Future Android 16+ (API 36+) {@code AppFunctionManager} native app integrations</li>
 * </ul>
 *
 * <p>All discovered tools are advertised as {@code defer_loading: true} in {@link LfmToolCatalog}
 * so the static system prompt stays compact, and every execution routes through
 * {@link ToolCoordinator} with explicit user confirmation by default.
 */
public interface ExternalToolProvider {
    /** Stable provider prefix (e.g. {@code "shortcut"}, {@code "mcp"}, {@code "appfn"}). */
    String providerId();

    /** Returns currently enabled deferred {@link LfmToolCatalog.ToolSpec} entries for discovery. */
    List<LfmToolCatalog.ToolSpec> discoverTools();

    /** Returns true when this provider owns and currently allows {@code toolWireName}. */
    boolean supportsTool(String toolWireName);

    /** Human-readable preview for the ToolCoordinator confirmation card. */
    String formatForDisplay(String toolWireName, Map<String, Object> arguments);

    /** Executes an already user-approved external tool call. */
    AndroidActionExecutor.ActionResult execute(String toolWireName, Map<String, Object> arguments);
}
