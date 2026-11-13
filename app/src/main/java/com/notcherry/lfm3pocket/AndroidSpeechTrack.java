package com.notcherry.lfm3pocket;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Production AudioTrack adapter for local Omni spoken-response playback. */
final class AndroidSpeechTrack implements PlaybackController.Track {
    private AudioTrack track;

    @Override public void start(int sampleRateHz, int channels, int bitsPerSample) {
        if (channels != 1 || bitsPerSample != 16)
            throw new IllegalArgumentException("Only mono PCM16 playback is supported");
        int min = AudioTrack.getMinBufferSize(sampleRateHz, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IllegalStateException("AudioTrack buffer unavailable");
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sampleRateHz)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(min, sampleRateHz))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.play();
    }

    @Override public void write(byte[] pcm) {
        if (track == null) throw new IllegalStateException("Track is not started");
        int written = 0;
        while (written < pcm.length) {
            int n = track.write(pcm, written, pcm.length - written);
            if (n < 0) throw new IllegalStateException("AudioTrack write failed: " + n);
            written += n;
        }
    }

    @Override public void stop() {
        if (track == null) return;
        try { track.pause(); } catch (IllegalStateException ignored) {}
        try { track.flush(); } catch (IllegalStateException ignored) {}
        try { track.stop(); } catch (IllegalStateException ignored) {}
    }

    @Override public void close() {
        if (track == null) return;
        track.release();
        track = null;
    }
}
