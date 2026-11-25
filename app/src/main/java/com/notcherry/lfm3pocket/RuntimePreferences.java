package com.notcherry.lfm3pocket;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Model path and background-mode switches. The file name stays {@code MainActivity}
 * so instrumentation that seeds that file, and the service, share it with chat.
 *
 * <p>Do not switch callers to {@code Activity.getPreferences}. That API names the
 * file from the install id. The install id is {@code dev.notcherry.hanami} while
 * the activity class is still in {@code com.notcherry.lfm3pocket}, so
 * {@code getPreferences} would open a different file and ignore a seeded model.
 */
public final class RuntimePreferences {
    static final String FILE = "MainActivity";

    /**
     * Mirrors {@code Activity.getLocalClassName}, which {@code getPreferences} uses
     * as the file name. When the install id and the class package differ, the file
     * name is the full class name rather than the simple name.
     */
    static String activityPreferenceName(String installPackage, String className) {
        if (installPackage != null && className != null
                && className.startsWith(installPackage)
                && className.length() > installPackage.length()
                && className.charAt(installPackage.length()) == '.') {
            return className.substring(installPackage.length() + 1);
        }
        return className == null ? "" : className;
    }

    private final SharedPreferences prefs;

    public RuntimePreferences(Context context) {
        // attachBaseContext may run before Application is bound; fall back to the base context.
        Context store = null;
        try { store = context == null ? null : context.getApplicationContext(); } catch (RuntimeException ignored) {}
        if (store == null) store = context;
        prefs = store.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public SharedPreferences raw() { return prefs; }

    public boolean keepLoaded() { return prefs.getBoolean("keep_loaded", true); }

    public int idleMinutes() {
        int value = prefs.getInt("idle_unload_minutes", 0);
        return value == 15 || value == 60 ? value : 0;
    }

    public boolean lockPreview() { return prefs.getBoolean("lock_screen_preview", false); }

    /** Opt-in {@code 127.0.0.1} prompt listener. Off by default; cannot approve tools. */
    public boolean loopbackEnabled() { return prefs.getBoolean("loopback_api", false); }

    /** Opt-in network tools such as Open-Meteo weather. Off by default. */
    public boolean networkToolsEnabled() { return prefs.getBoolean("network_tools", false); }

    /** English-only tiny-voca TTS for Speak reply (off by default). */
    public boolean tinyVocaEnglishTtsEnabled() { return prefs.getBoolean("tiny_voca_english_tts", false); }

    /** Opt-in notification access for summarize-on-request. Default off. */
    public boolean notificationAccessEnabled() { return prefs.getBoolean("notification_access_enabled", false); }

    /** Per-app allowlist for notification access. Empty means deny all. */
    public java.util.Set<String> notificationAllowlist() {
        java.util.Set<String> stored = prefs.getStringSet("notification_allowlist", java.util.Collections.emptySet());
        java.util.LinkedHashSet<String> copy = new java.util.LinkedHashSet<>();
        if (stored != null) {
            for (String value : stored) {
                if (value != null) {
                    String trimmed = value.trim();
                    if (!trimmed.isEmpty()) copy.add(trimmed);
                }
            }
        }
        return java.util.Collections.unmodifiableSet(copy);
    }

    /** Speech input backend. Defaults to the existing LFM3-Omni audio-in path. */
    public SpeechInputMode speechInputMode() {
        return SpeechInputMode.fromWire(prefs.getString("speech_input_mode", SpeechInputMode.OMNI_AUDIO.wireName));
    }

    /** Local Cactus Whistle model path (.cact) for optional STT. Empty means unconfigured. */
    public String cactusWhistleModelPath() {
        return prefs.getString("cactus_whistle_model", "");
    }

    /** Target calendar for write tools; empty means no calendar picked (writes blocked). */
    public String calendarWriteId() { return prefs.getString("calendar_write_id", ""); }

    /** Optional human-readable label for the picked calendar (Settings display only). */
    public String calendarWriteLabel() { return prefs.getString("calendar_write_label", ""); }

    /** Persisted SAF tree URI for the offline Local Workspace folder; empty means unmounted. */
    public String workspaceTreeUri() { return prefs.getString("workspace_tree_uri", ""); }

    /** Human-readable display label for the mounted Local Workspace folder. */
    public String workspaceLabel() { return prefs.getString("workspace_label", ""); }

    /**
     * App-level interface size multiplier (0.80–1.30). Applied via densityDpi so typography and
     * spacing scale together on top of the system font scale. Default is {@link InterfaceSize#DEFAULT}.
     */
    public float interfaceScale() {
        float stored = prefs.getFloat("interface_scale", InterfaceSize.DEFAULT);
        return InterfaceSize.clamp(stored);
    }

    public void setInterfaceScale(float value) {
        prefs.edit().putFloat("interface_scale", InterfaceSize.clamp(value)).apply();
    }

    public void setKeepLoaded(boolean value) { prefs.edit().putBoolean("keep_loaded", value).apply(); }

    public void setIdleMinutes(int value) {
        int stored = value == 15 || value == 60 ? value : 0;
        prefs.edit().putInt("idle_unload_minutes", stored).apply();
    }

    public void setLockPreview(boolean value) { prefs.edit().putBoolean("lock_screen_preview", value).apply(); }

    public void setLoopbackEnabled(boolean value) { prefs.edit().putBoolean("loopback_api", value).apply(); }

    public void setNetworkToolsEnabled(boolean value) { prefs.edit().putBoolean("network_tools", value).apply(); }

    public void setTinyVocaEnglishTtsEnabled(boolean value) {
        prefs.edit().putBoolean("tiny_voca_english_tts", value).apply();
    }

    public void setNotificationAccessEnabled(boolean value) {
        prefs.edit().putBoolean("notification_access_enabled", value).apply();
    }

    public void setNotificationAllowlist(java.util.Set<String> packages) {
        java.util.LinkedHashSet<String> copy = new java.util.LinkedHashSet<>();
        if (packages != null) {
            for (String value : packages) {
                if (value == null) continue;
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) copy.add(trimmed);
            }
        }
        prefs.edit().putStringSet("notification_allowlist", copy).apply();
    }

    public void setSpeechInputMode(SpeechInputMode mode) {
        SpeechInputMode value = mode == null ? SpeechInputMode.OMNI_AUDIO : mode;
        prefs.edit().putString("speech_input_mode", value.wireName).apply();
    }

    public void setCactusWhistleModelPath(String path) {
        prefs.edit().putString("cactus_whistle_model", path == null ? "" : path.trim()).apply();
    }

    public void setCalendarWriteTarget(String id, String label) {
        prefs.edit()
                .putString("calendar_write_id", id == null ? "" : id)
                .putString("calendar_write_label", label == null ? "" : label)
                .apply();
    }

    public void setWorkspaceTarget(String treeUri, String label) {
        prefs.edit()
                .putString("workspace_tree_uri", treeUri == null ? "" : treeUri.trim())
                .putString("workspace_label", label == null ? "" : label.trim())
                .apply();
    }

    /** Serialized JSON array of user-defined offline local Intent/URI shortcuts. */
    public String customShortcutsJson() { return prefs.getString("custom_shortcuts_json", "[]"); }

    public void setCustomShortcutsJson(String json) {
        prefs.edit().putString("custom_shortcuts_json", json == null ? "[]" : json).apply();
    }

    /** Serialized JSON array of configured MCP Streamable HTTP servers and cached tool lists. */
    public String mcpServersJson() { return prefs.getString("mcp_servers_json", "[]"); }

    public void setMcpServersJson(String json) {
        prefs.edit().putString("mcp_servers_json", json == null ? "[]" : json).apply();
    }

    /** Per-install secret required by the opt-in localhost prompt API. Never logged. */
    public synchronized String loopbackToken() {
        String token = prefs.getString("loopback_token", "");
        if (token != null && !token.isEmpty()) return token;
        byte[] bytes = new byte[24];
        new java.security.SecureRandom().nextBytes(bytes);
        token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        prefs.edit().putString("loopback_token", token).apply();
        return token;
    }
}
