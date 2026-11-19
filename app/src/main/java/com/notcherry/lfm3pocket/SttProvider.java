package com.notcherry.lfm3pocket;

import android.content.Context;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/** On-device speech-to-text backend. Implementations must never use network access. */
public interface SttProvider {
    SpeechInputMode mode();

    /** Human-readable name for settings/UI. */
    String displayName(Context context);

    /** Static/local availability check (runtime/model present). */
    SttAvailability availability(Context context);

    /**
     * Transcribe a 16 kHz mono PCM16 WAV recording. Implementations must honor cancellation,
     * delete no caller-owned files, and return a non-null result.
     */
    SttResult transcribe(Context context, File wavFile, AtomicBoolean cancelled);
}
