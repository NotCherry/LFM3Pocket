package com.notcherry.lfm3pocket;

import android.content.Context;

/** Resolves the active speech input provider from persisted settings. */
public final class SpeechInputProviders {
    private SpeechInputProviders() {}

    public static SttProvider forMode(SpeechInputMode mode) {
        if (mode == SpeechInputMode.CACTUS_WHISTLE_STT) return new CactusWhistleSttProvider();
        return new OmniSpeechInputProvider();
    }

    public static SttProvider current(Context context) {
        RuntimePreferences prefs = new RuntimePreferences(context);
        return forMode(prefs.speechInputMode());
    }

    public static boolean usesSttTextPipeline(Context context) {
        return new RuntimePreferences(context).speechInputMode() == SpeechInputMode.CACTUS_WHISTLE_STT;
    }
}
