package com.notcherry.lfm3pocket;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Model path and background-mode switches. The file name stays {@code MainActivity}
 * so existing instrumentation that seeds that file keeps working, and the service
 * can open it without an activity.
 */
public final class RuntimePreferences {
    static final String FILE = "MainActivity";
    private final SharedPreferences prefs;

    public RuntimePreferences(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public SharedPreferences raw() { return prefs; }

    public boolean keepLoaded() { return prefs.getBoolean("keep_loaded", true); }

    public int idleMinutes() {
        int value = prefs.getInt("idle_unload_minutes", 0);
        return value == 15 || value == 60 ? value : 0;
    }

    public boolean lockPreview() { return prefs.getBoolean("lock_screen_preview", false); }

    public void setKeepLoaded(boolean value) { prefs.edit().putBoolean("keep_loaded", value).apply(); }

    public void setIdleMinutes(int value) {
        int stored = value == 15 || value == 60 ? value : 0;
        prefs.edit().putInt("idle_unload_minutes", stored).apply();
    }

    public void setLockPreview(boolean value) { prefs.edit().putBoolean("lock_screen_preview", value).apply(); }
}
