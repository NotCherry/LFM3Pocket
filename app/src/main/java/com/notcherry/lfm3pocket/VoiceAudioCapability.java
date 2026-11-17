package com.notcherry.lfm3pocket;

/** Whether the resident model pair can hear audio for voice actions. */
public final class VoiceAudioCapability {
    private VoiceAudioCapability() {}

    public static boolean voiceActionsEnabled(InferenceEngine engine, long modelHandle) {
        return modelHandle != 0L && engine != null && engine.supportsVoiceAudioIn(modelHandle);
    }
}