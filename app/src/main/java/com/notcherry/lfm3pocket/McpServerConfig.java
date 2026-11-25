package com.notcherry.lfm3pocket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Configuration and cached tool definitions for one MCP Streamable HTTP server. */
public final class McpServerConfig {
    public static final class ToolDef {
        public final String name;
        public final String description;
        public final List<String> parameterKeys;
        public final String inputSchemaJson;

        public ToolDef(String name, String description, List<String> parameterKeys, String inputSchemaJson) {
            this.name = name == null ? "" : name.trim();
            this.description = description == null ? "" : description.trim();
            this.parameterKeys = parameterKeys == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(parameterKeys));
            this.inputSchemaJson = (inputSchemaJson == null || inputSchemaJson.trim().isEmpty())
                    ? "{\"type\":\"object\",\"properties\":{}}"
                    : inputSchemaJson.trim();
        }
    }

    public final String id;
    public final String label;
    public final String url;
    public final String bearerToken;
    public final boolean enabled;
    public final boolean allowLanHttp;
    public final List<ToolDef> cachedTools;

    public McpServerConfig(String id, String label, String url, String bearerToken,
                           boolean enabled, boolean allowLanHttp, List<ToolDef> cachedTools) {
        String cleanId = CustomShortcut.sanitizeId(id);
        if (cleanId.isEmpty()) throw new IllegalArgumentException("Server id must contain letters or digits");
        String err = McpEndpointPolicy.validationError(url, allowLanHttp);
        if (err != null) throw new IllegalArgumentException(err);
        this.id = cleanId;
        this.label = (label == null || label.trim().isEmpty()) ? cleanId : label.trim();
        this.url = url.trim();
        this.bearerToken = bearerToken == null ? "" : bearerToken.trim();
        this.enabled = enabled;
        this.allowLanHttp = allowLanHttp;
        this.cachedTools = cachedTools == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(cachedTools));
    }

    public McpServerConfig withCachedTools(List<ToolDef> tools) {
        return new McpServerConfig(id, label, url, bearerToken, enabled, allowLanHttp, tools);
    }

    public McpServerConfig withEnabled(boolean nextEnabled) {
        return new McpServerConfig(id, label, url, bearerToken, nextEnabled, allowLanHttp, cachedTools);
    }

    public String wireNameFor(String remoteToolName) {
        return "mcp." + id + "." + CustomShortcut.sanitizeId(remoteToolName);
    }

    public LfmToolCatalog.ToolSpec toToolSpec(ToolDef def) {
        String wire = wireNameFor(def.name);
        String desc = "[" + label + "] " + (def.description.isEmpty() ? def.name : def.description);
        String schema = "{\"name\":\"" + CustomShortcut.escapeJson(wire)
                + "\",\"defer_loading\":true,\"description\":\"" + CustomShortcut.escapeJson(desc)
                + "\",\"parameters\":" + def.inputSchemaJson + "}";
        String keywords = (id + " " + label + " " + def.name + " " + def.description + " mcp external tool")
                .toLowerCase(Locale.ROOT);
        return new LfmToolCatalog.ToolSpec(wire, schema, def.parameterKeys, keywords, true, c -> true);
    }

    public static String serializeList(List<McpServerConfig> servers) {
        if (servers == null || servers.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < servers.size(); i++) {
            McpServerConfig s = servers.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"").append(CustomShortcut.escapeJson(s.id))
                    .append("\",\"label\":\"").append(CustomShortcut.escapeJson(s.label))
                    .append("\",\"url\":\"").append(CustomShortcut.escapeJson(s.url))
                    .append("\",\"token\":\"").append(CustomShortcut.escapeJson(s.bearerToken))
                    .append("\",\"enabled\":").append(s.enabled)
                    .append(",\"allowLanHttp\":").append(s.allowLanHttp)
                    .append(",\"tools\":[");
            for (int t = 0; t < s.cachedTools.size(); t++) {
                ToolDef d = s.cachedTools.get(t);
                if (t > 0) sb.append(',');
                sb.append("{\"name\":\"").append(CustomShortcut.escapeJson(d.name))
                        .append("\",\"description\":\"").append(CustomShortcut.escapeJson(d.description))
                        .append("\",\"schema\":\"").append(CustomShortcut.escapeJson(d.inputSchemaJson))
                        .append("\",\"keys\":[");
                for (int k = 0; k < d.parameterKeys.size(); k++) {
                    if (k > 0) sb.append(',');
                    sb.append('"').append(CustomShortcut.escapeJson(d.parameterKeys.get(k))).append('"');
                }
                sb.append("]}");
            }
            sb.append("]}");
        }
        sb.append(']');
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static List<McpServerConfig> deserializeList(String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyList();
        try {
            Object parsed = StrictToolJson.parseBounded(json, 262_144, 10, 256);
            if (!(parsed instanceof List)) return Collections.emptyList();
            List<McpServerConfig> out = new ArrayList<>();
            for (Object item : (List<?>) parsed) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) item;
                String id = str(m.get("id"));
                String label = str(m.get("label"));
                String url = str(m.get("url"));
                String token = str(m.get("token"));
                boolean enabled = Boolean.TRUE.equals(m.get("enabled"));
                boolean allowLan = Boolean.TRUE.equals(m.get("allowLanHttp"));
                List<ToolDef> tools = new ArrayList<>();
                Object rawTools = m.get("tools");
                if (rawTools instanceof List) {
                    for (Object tItem : (List<?>) rawTools) {
                        if (!(tItem instanceof Map)) continue;
                        Map<String, Object> tm = (Map<String, Object>) tItem;
                        String tName = str(tm.get("name"));
                        if (tName.isEmpty()) continue;
                        String tDesc = str(tm.get("description"));
                        String tSchema = str(tm.get("schema"));
                        List<String> keys = new ArrayList<>();
                        if (tm.get("keys") instanceof List) {
                            for (Object k : (List<?>) tm.get("keys")) {
                                if (k instanceof String && !((String) k).isEmpty()) keys.add((String) k);
                            }
                        }
                        tools.add(new ToolDef(tName, tDesc, keys, tSchema));
                    }
                }
                try {
                    out.add(new McpServerConfig(id, label, url, token, enabled, allowLan, tools));
                } catch (IllegalArgumentException ignored) {}
            }
            return out;
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    private static String str(Object value) {
        return value instanceof String ? (String) value : "";
    }
}
