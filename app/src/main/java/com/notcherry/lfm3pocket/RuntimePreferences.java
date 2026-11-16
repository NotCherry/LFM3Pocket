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
        Context store = context.getApplicationContext();
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
}
