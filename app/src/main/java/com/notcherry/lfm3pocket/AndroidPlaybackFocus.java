package com.notcherry.lfm3pocket;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;

/** Requests transient media audio focus for local spoken-response playback. */
final class AndroidPlaybackFocus {
    private final AudioManager manager;
    private AudioFocusRequest request;
    private final AudioManager.OnAudioFocusChangeListener listener =
            change -> { /* PlaybackController.stop() is driven by the UI Stop control. */ };

    AndroidPlaybackFocus(Context context) {
        manager = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
    }

    boolean request() {
        if (manager == null) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setOnAudioFocusChangeListener(listener)
                    .build();
            return manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        return manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    void abandon() {
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= 26 && request != null) {
            manager.abandonAudioFocusRequest(request);
            request = null;
        } else {
            manager.abandonAudioFocus(listener);
        }
    }
}
