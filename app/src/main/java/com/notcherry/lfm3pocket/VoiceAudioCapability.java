package com.notcherry.lfm3pocket;

import android.content.SharedPreferences;

/** Whether the resident model pair can hear audio for voice actions. */
public final class VoiceAudioCapability {
    private VoiceAudioCapability() {}

    public static boolean voiceActionsEnabled(SharedPreferences prefs, long modelHandle) {
        if (modelHandle == 0L) return false;
        String projector = prefs == null ? "" : prefs.getString("mmproj", "");
        return projector != null && !projector.trim().isEmpty();
    }
}