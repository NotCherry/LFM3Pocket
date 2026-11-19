package com.notcherry.lfm3pocket;

import android.content.Context;

/** Whether voice actions can accept microphone input with the active model/provider. */
public final class VoiceAudioCapability {
    private VoiceAudioCapability() {}

    public static boolean voiceActionsEnabled(InferenceEngine engine, long modelHandle) {
        return modelHandle != 0L && engine != null && engine.supportsVoiceAudioIn(modelHandle);
    }

    public static boolean voiceActionsEnabled(Context context, InferenceEngine engine, long modelHandle) {
        if (modelHandle == 0L) return false;
        RuntimePreferences prefs = new RuntimePreferences(context);
        if (prefs.speechInputMode() == SpeechInputMode.CACTUS_WHISTLE_STT) {
            return SpeechInputProviders.current(context).availability(context).available;
        }
        return voiceActionsEnabled(engine, modelHandle);
    }

    public static String disabledReason(Context context, InferenceEngine engine, long modelHandle) {
        if (modelHandle == 0L) return context.getString(R.string.loading_model);
        RuntimePreferences prefs = new RuntimePreferences(context);
        if (prefs.speechInputMode() == SpeechInputMode.CACTUS_WHISTLE_STT) {
            SttAvailability availability = SpeechInputProviders.current(context).availability(context);
            if (!availability.available) {
                String detail = availability.message == null || availability.message.isEmpty()
                        ? context.getString(R.string.speech_input_cactus_status_missing)
                        : availability.message;
                return context.getString(R.string.speech_input_cactus_unavailable, detail);
            }
            return "";
        }
        if (engine == null || !engine.supportsVoiceAudioIn(modelHandle)) {
            return context.getString(R.string.voice_no_audio_model);
        }
        return "";
    }
}