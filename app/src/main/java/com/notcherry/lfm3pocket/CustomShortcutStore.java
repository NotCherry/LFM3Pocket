package com.notcherry.lfm3pocket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Persists user-defined offline local shortcuts and exposes them as an {@link ExternalToolProvider}.
 * Uses pure-Java JSON serialization so all persistence and dispatch logic runs in JVM unit tests.
 */
public final class CustomShortcutStore implements ExternalToolProvider {
    public interface UriLauncher {
        void launch(String renderedUri, String optionalPackageName) throws Exception;
    }

    private final Supplier<String> readJson;
    private final Consumer<String> writeJson;
    private final UriLauncher launcher;

    public CustomShortcutStore(Supplier<String> readJson, Consumer<String> writeJson, UriLauncher launcher) {
        if (readJson == null || writeJson == null || launcher == null) {
            throw new IllegalArgumentException("Missing shortcut store dependencies");
        }
        this.readJson = readJson;
        this.writeJson = writeJson;
        this.launcher = launcher;
    }

    public List<CustomShortcut> list() {
        return deserialize(readJson.get());
    }

    public void addOrReplace(CustomShortcut shortcut) {
        if (shortcut == null) throw new IllegalArgumentException("shortcut is required");
        List<CustomShortcut> current = new ArrayList<>(list());
        current.removeIf(item -> item.id.equals(shortcut.id));
        if (current.size() >= 32) {
            throw new IllegalStateException("Maximum of 32 custom shortcuts reached");
        }
        current.add(shortcut);
        writeJson.accept(serialize(current));
    }

    public boolean remove(String id) {
        List<CustomShortcut> current = new ArrayList<>(list());
        boolean removed = current.removeIf(item -> item.id.equals(id));
        if (removed) writeJson.accept(serialize(current));
        return removed;
    }

    public CustomShortcut findByWireName(String wireName) {
        if (wireName == null || !wireName.startsWith("shortcut.")) return null;
        for (CustomShortcut s : list()) {
            if (wireName.equals(s.wireName())) return s;
        }
        return null;
    }

    @Override public String providerId() { return "shortcut"; }

    @Override public List<LfmToolCatalog.ToolSpec> discoverTools() {
        List<CustomShortcut> items = list();
        List<LfmToolCatalog.ToolSpec> out = new ArrayList<>(items.size());
        for (CustomShortcut s : items) out.add(s.toToolSpec());
        return out;
    }

    @Override public boolean supportsTool(String toolWireName) {
        return findByWireName(toolWireName) != null;
    }

    @Override public String formatForDisplay(String toolWireName, Map<String, Object> arguments) {
        CustomShortcut s = findByWireName(toolWireName);
        if (s == null) return "Run shortcut\nTool: " + toolWireName;
        StringBuilder out = new StringBuilder("Run local shortcut: ").append(s.label);
        if (!s.packageName.isEmpty()) out.append("\nApp: ").append(s.packageName);
        try {
            String uri = s.renderUri(arguments);
            out.append("\nURI: ").append(uri.length() > 220 ? uri.substring(0, 217) + "…" : uri);
        } catch (IllegalArgumentException e) {
            out.append("\nTemplate: ").append(s.uriTemplate);
        }
        return out.toString();
    }

    @Override public AndroidActionExecutor.ActionResult execute(String toolWireName, Map<String, Object> arguments) {
        CustomShortcut s = findByWireName(toolWireName);
        if (s == null) {
            return new AndroidActionExecutor.ActionResult(false, "UNKNOWN_SHORTCUT",
                    "That custom shortcut is no longer configured.");
        }
        try {
            String uri = s.renderUri(arguments);
            launcher.launch(uri, s.packageName);
            return new AndroidActionExecutor.ActionResult(true, "SHORTCUT_LAUNCHED",
                    "Opened local shortcut \"" + s.label + "\".");
        } catch (IllegalArgumentException e) {
            return new AndroidActionExecutor.ActionResult(false, "INVALID_ARGUMENTS", e.getMessage());
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new AndroidActionExecutor.ActionResult(false, "SHORTCUT_FAILED",
                    "Could not open shortcut \"" + s.label + "\": " + msg);
        }
    }

    public static String serialize(List<CustomShortcut> shortcuts) {
        if (shortcuts == null || shortcuts.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < shortcuts.size(); i++) {
            CustomShortcut s = shortcuts.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"").append(CustomShortcut.escapeJson(s.id))
                    .append("\",\"label\":\"").append(CustomShortcut.escapeJson(s.label))
                    .append("\",\"description\":\"").append(CustomShortcut.escapeJson(s.description))
                    .append("\",\"uri\":\"").append(CustomShortcut.escapeJson(s.uriTemplate))
                    .append("\",\"package\":\"").append(CustomShortcut.escapeJson(s.packageName))
                    .append("\"}");
        }
        sb.append(']');
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static List<CustomShortcut> deserialize(String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyList();
        try {
            Object parsed = StrictToolJson.parse(json);
            if (!(parsed instanceof List)) return Collections.emptyList();
            List<CustomShortcut> out = new ArrayList<>();
            for (Object item : (List<?>) parsed) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) item;
                String id = str(m.get("id"));
                String label = str(m.get("label"));
                String desc = str(m.get("description"));
                String uri = str(m.get("uri"));
                String pkg = str(m.get("package"));
                try {
                    out.add(new CustomShortcut(id, label, desc, uri, pkg));
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
