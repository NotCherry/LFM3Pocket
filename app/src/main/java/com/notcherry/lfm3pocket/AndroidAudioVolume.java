package com.notcherry.lfm3pocket;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;

/** AudioManager adapter that changes only the validated legacy stream and uses no UI/playback flags. */
final class AndroidAudioVolume implements VolumeController.Audio {
    private final AudioManager manager;

    AndroidAudioVolume(Context context) {
        AudioManager found = null;
        try { found = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE); }
        catch (RuntimeException ignored) { /* An unattached Application is used by a default-deny contract test. */ }
        manager = found;
    }

    @Override public boolean isVolumeFixed() {
        return requireManager().isVolumeFixed();
    }

    @Override public int minVolume(VolumeController.Stream stream) {
        AudioManager audio = requireManager();
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? audio.getStreamMinVolume(streamType(stream)) : 0;
    }

    @Override public int maxVolume(VolumeController.Stream stream) {
        return requireManager().getStreamMaxVolume(streamType(stream));
    }

    @Override public int currentVolume(VolumeController.Stream stream) {
        return requireManager().getStreamVolume(streamType(stream));
    }

    @Override public void setVolume(VolumeController.Stream stream, int index) {
        requireManager().setStreamVolume(streamType(stream), index, 0);
    }

    private AudioManager requireManager() {
        if (manager == null) throw new IllegalStateException("Audio service is unavailable");
        return manager;
    }

    private static int streamType(VolumeController.Stream stream) {
        switch (stream) {
            case MUSIC: return AudioManager.STREAM_MUSIC;
            case ALARM: return AudioManager.STREAM_ALARM;
            case RING: return AudioManager.STREAM_RING;
            case NOTIFICATION: return AudioManager.STREAM_NOTIFICATION;
            default: throw new IllegalArgumentException("Unsupported audio stream");
        }
    }
}
