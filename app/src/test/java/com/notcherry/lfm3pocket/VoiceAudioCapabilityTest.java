package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

public final class VoiceAudioCapabilityTest {
    @Test public void requiresProjectorPath() {
        SharedPreferences empty = new MapPreferences();
        assertFalse(VoiceAudioCapability.voiceActionsEnabled(empty, 42L));
        MapPreferences withProjector = new MapPreferences();
        withProjector.put("mmproj", "content://x/mmproj.gguf");
        assertTrue(VoiceAudioCapability.voiceActionsEnabled(withProjector, 42L));
        assertFalse(VoiceAudioCapability.voiceActionsEnabled(withProjector, 0L));
    }

    private static final class MapPreferences implements SharedPreferences {
        private final java.util.Map<String, String> map = new java.util.HashMap<>();

        void put(String key, String value) { map.put(key, value); }

        @Override public String getString(String key, String defValue) {
            return map.getOrDefault(key, defValue);
        }

        @Override public java.util.Map<String, ?> getAll() { return map; }
        @Override public boolean getBoolean(String key, boolean defValue) { return defValue; }
        @Override public float getFloat(String key, float defValue) { return defValue; }
        @Override public int getInt(String key, int defValue) { return defValue; }
        @Override public long getLong(String key, long defValue) { return defValue; }
        @Override public java.util.Set<String> getStringSet(String key, java.util.Set<String> defValue) {
            return defValue;
        }
        @Override public boolean contains(String key) { return map.containsKey(key); }
        @Override public Editor edit() { throw new UnsupportedOperationException(); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
    }
}