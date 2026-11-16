package com.notcherry.lfm3pocket;

import android.content.Intent;
import android.service.voice.VoiceInteractionService;

/**
 * Minimal VoiceInteractionService so Hanami can be selected as the default
 * assistant. Session/voice capture is intentionally not implemented yet; the
 * assist activity opens a fresh chat instead.
 */
public final class HanamiVoiceInteractionService extends VoiceInteractionService {
    @Override public void onLaunchVoiceAssistFromKeyguard() {
        startActivity(new Intent(this, AssistEntryActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
