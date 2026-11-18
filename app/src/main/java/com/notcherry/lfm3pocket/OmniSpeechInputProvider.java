package com.notcherry.lfm3pocket;

import android.content.Context;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/** Marker provider for the existing LFM3-Omni audio-in path (default). */
public final class OmniSpeechInputProvider implements SttProvider {
    @Override public SpeechInputMode mode() { return SpeechInputMode.OMNI_AUDIO; }

    @Override public String displayName(Context context) {
        return context == null ? "LFM3-Omni audio" : context.getString(R.string.speech_input_omni);
    }

    @Override public SttAvailability availability(Context context) {
        return SttAvailability.ready();
    }

    @Override public SttResult transcribe(Context context, File wavFile, AtomicBoolean cancelled) {
        return SttResult.unavailable("Use the LFM3-Omni audio path");
    }
}
