package com.notcherry.lfm3pocket;

import android.content.Intent;
import android.service.voice.VoiceInteractionService;

/** Enables the system assistant gesture to route into Hanami's assist entry. */
public final class HanamiVoiceInteractionService extends VoiceInteractionService {
    @Override public void onLaunchVoiceAssistFromKeyguard() {
        startActivity(new Intent(this, AssistEntryActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
