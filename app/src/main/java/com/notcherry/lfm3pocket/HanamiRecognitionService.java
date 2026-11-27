package com.notcherry.lfm3pocket;

import android.content.Intent;
import android.os.RemoteException;
import android.speech.RecognitionService;

/**
 * Placeholder recognition service required by VoiceInteractionService metadata.
 * Hanami does not perform system speech recognition yet.
 */
public final class HanamiRecognitionService extends RecognitionService {
    @Override protected void onStartListening(Intent recognizerIntent, Callback listener) {
        try {
            listener.error(android.speech.SpeechRecognizer.ERROR_CLIENT);
        } catch (RemoteException ignored) { }
    }

    @Override protected void onCancel(Callback listener) { }

    @Override protected void onStopListening(Callback listener) { }
}
