package com.notcherry.lfm3pocket;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Thread-confined AudioTrack MODE_STREAM adapter for PCM16 mono spoken responses. */
final class AndroidAudioTrackPlayer implements PlaybackController.Player {
    private final AudioTrack track;

    AndroidAudioTrackPlayer() {
        int minimum = AudioTrack.getMinBufferSize(SpeechAudio.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IllegalStateException("This device cannot play 16 kHz PCM audio");
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(SpeechAudio.SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(Math.max(minimum, 8192))
                .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            throw new IllegalStateException("AudioTrack initialization failed");
        }
    }

    @Override public void start(short[] pcm) {
        track.play();
    }

    @Override public int write(short[] pcm, int offset, int count) {
        return track.write(pcm, offset, count, AudioTrack.WRITE_NON_BLOCKING);
    }

    @Override public void stop() {
        try { track.pause(); } catch (IllegalStateException ignored) {}
        try { track.flush(); } catch (IllegalStateException ignored) {}
        try { track.stop(); } catch (IllegalStateException ignored) {}
    }

    @Override public void close() {
        try { stop(); } finally { track.release(); }
    }
}
