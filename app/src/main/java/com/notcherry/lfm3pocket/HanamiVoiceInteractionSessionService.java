package com.notcherry.lfm3pocket;

import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/** Routes the system assist session into Hanami's single assistant entry path. */
public final class HanamiVoiceInteractionSessionService extends VoiceInteractionSessionService {
    @Override public VoiceInteractionSession onNewSession(Bundle args) {
        return new VoiceInteractionSession(this) {
            @Override public void onShow(Bundle args, int showFlags) {
                super.onShow(args, showFlags);
                startActivity(new Intent(getContext(), AssistEntryActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                hide();
            }
        };
    }
}
