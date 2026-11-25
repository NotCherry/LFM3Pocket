# Offline Workspace, Custom Shortcuts, and Open MCP Client

Hanami is **offline-first**: local inference, calendar, contacts, local workspace/vault notes, and custom local app shortcuts all work with zero internet access, zero telemetry, and zero proprietary cloud SDK lock-in.

---

## 1. Offline Local Workspace & Markdown Vault (Obsidian / Syncthing / Notes)

1. Open **Settings → LOCAL WORKSPACE** and tap **Pick folder**.
2. Choose any folder via Android's Storage Access Framework (for example, your Obsidian vault, Syncthing folder, or Notes directory).
3. Hanami persists read/write access strictly to that folder and exposes coding-harness tools to LFM3:
   - Read-only (auto-executed inside the bounded agent loop): `fs_list`, `fs_search`, `fs_read`
   - Write actions (always require explicit user approval with a preview/diff card): `note_create`, `fs_append`, `fs_edit`, `fs_delete`
4. You can also attach **PDF** or plain-text/Markdown files directly from Chat or via Android's **Share** menu (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`). Per **ADR 0025**, any write action proposed while reading attached documents always requires confirmation with a taint warning, and outbound network tools never auto-execute from document-tainted turns.

---

## 2. Offline Contacts & Custom Local App Shortcuts

- **Contacts (`lookup_contact`):** Open **Settings → EXTERNAL TOOLS & MCP → Grant Contacts access** (opt-in `READ_CONTACTS`). LFM3 can then look up names, numbers, or emails offline before drafting messages (`draft_message`).
- **Custom Local Shortcuts:** Tap **Add local shortcut** to expose any local deep link with `{{param}}` placeholders as a deferred LFM3 tool (discoverable via `search_tools`):
  - **Obsidian new note:** `obsidian://new?name={{title}}&content={{content}}`
  - **OsmAnd / Maps search:** `geo:0,0?q={{query}}`
  - **Email draft:** `mailto:{{to}}?subject={{subject}}&body={{body}}`
  - Blocked schemes (`file:`, `content:`, `javascript:`, `intent:`, `data:`) are rejected, and every shortcut launch requires explicit user approval showing the rendered URI.

---

## 3. Open MCP Client (Streamable HTTP JSON-RPC 2.0)

Hanami implements a lightweight, pure-Java **Model Context Protocol (MCP) Streamable HTTP** client (`initialize`, `tools/list`, `tools/call`, SSE & JSON responses) with no third-party SDK bloat. All synced MCP tools are cached locally and advertised as `defer_loading: true` so LFM3 discovers them via `search_tools` without bloating the system prompt.

### A. 100% Offline Local MCP Server (Termux on the Same Phone)
1. Run any MCP Streamable HTTP server inside Termux bound to `127.0.0.1` (e.g. `http://127.0.0.1:3000/mcp` for local Git, Python, SQLite, or shell tooling).
2. In Hanami **Settings → EXTERNAL TOOLS & MCP → Add MCP server**:
   - Name: `Termux`
   - URL: `http://127.0.0.1:3000/mcp`
3. Tap **Sync tools**. Loopback (`127.0.0.1` / `localhost`) works completely offline even in Airplane Mode.

### B. Self-Hosted Homelab MCP on Private LAN (Home Assistant, n8n, MetaMCP)
1. Prefer `https://` via Tailscale/Caddy/reverse proxy, or enable **Allow local LAN HTTP** only for private RFC1918 addresses (`10.x`, `192.168.x`, `172.16-31.x`, `*.local`).
2. Add your optional Bearer token and tap **Sync tools**.

### C. Optional Remote HTTPS MCP Hubs (Composio MCP, GitHub MCP, Notion MCP)
- Any remote MCP server over **`https://`** works out of the box without any vendor SDK:
  - Example: **Composio MCP** URL (`https://mcp.composio.dev/...`) with your API key/Bearer token, or GitHub/Notion/Linear hosted MCP servers.
- Public `http://` URLs are strictly rejected by both `McpEndpointPolicy` and Android `network_security_config.xml`.
- Every MCP tool call defaults to **user confirmation required**, showing the exact server name, endpoint, tool name, and arguments, and wraps responses with `UNTRUSTED_MCP` prompt boundaries.

---

## 4. Android 16+ AppFunctions Readiness

All external integrations (`CustomShortcutStore`, `McpToolProvider`) share the `ExternalToolProvider` interface (`discoverTools()`, `supportsTool()`, `formatForDisplay()`, `execute()`). When Hanami raises `compileSdk` to **API 36 (Android 16)**, an `AppFunctionsToolProvider` backed by `android.app.appfunctions.AppFunctionManager` can plug directly into `Lfm3PocketApplication.externalToolProviders()` with zero changes to `LfmToolCatalog`, `LfmToolGrammar`, `ChatSession`, or `ToolCoordinator`.
