package com.notcherry.lfm3pocket;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Production AudioTrack adapter for local spoken-response playback. */
final class AndroidSpeechTrack implements PlaybackController.Track {
    private volatile AudioTrack track;
    private volatile boolean aborted;
    private int sampleRateHz;
    private long totalFramesWritten;

    @Override public void start(int sampleRateHz, int channels, int bitsPerSample) {
        if (channels != 1 || bitsPerSample != 16)
            throw new IllegalArgumentException("Only mono PCM16 playback is supported");
        int min = AudioTrack.getMinBufferSize(sampleRateHz, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IllegalStateException("AudioTrack buffer unavailable");
        aborted = false;
        this.sampleRateHz = sampleRateHz;
        this.totalFramesWritten = 0L;
        AudioTrack built = new AudioTrack.Builder()
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
        track = built;
        built.play();
    }

    @Override public void write(byte[] pcm) {
        AudioTrack current = track;
        if (current == null) throw new IllegalStateException("Track is not started");
        int written = 0;
        while (written < pcm.length && !aborted) {
            int n = current.write(pcm, written, pcm.length - written);
            if (n < 0) {
                if (aborted) return;
                throw new IllegalStateException("AudioTrack write failed: " + n);
            }
            if (n == 0) break;
            written += n;
            totalFramesWritten += n / 2L;
        }
    }

    @Override public void drain() throws InterruptedException {
        AudioTrack current = track;
        if (current == null || aborted || totalFramesWritten <= 0L) return;
        try {
            // In MODE_STREAM, stop() without pause()/flush() drains buffered audio cleanly.
            current.stop();
        } catch (IllegalStateException ignored) {
            return;
        }
        long expectedMs = Math.max(400L, (totalFramesWritten * 1000L) / Math.max(8000, sampleRateHz) + 800L);
        long maxWaitMs = Math.min(expectedMs, 5_000L);
        long deadline = System.currentTimeMillis() + maxWaitMs;
        long lastPos = -1L;
        long stalledSince = 0L;
        while (!aborted && System.currentTimeMillis() < deadline) {
            int head;
            try {
                head = current.getPlaybackHeadPosition();
            } catch (IllegalStateException ignored) {
                break;
            }
            long unsignedHead = head & 0xffffffffL;
            if (unsignedHead >= totalFramesWritten) break;
            if (lastPos > 0L && unsignedHead < lastPos) break;
            long now = System.currentTimeMillis();
            if (unsignedHead == lastPos) {
                if (stalledSince == 0L) {
                    stalledSince = now;
                } else {
                    long stallLimit = unsignedHead == 0L ? 600L : 300L;
                    if (now - stalledSince >= stallLimit) break;
                }
            } else {
                lastPos = unsignedHead;
                stalledSince = 0L;
            }
            Thread.sleep(15L);
        }
    }

    @Override public void stop() {
        aborted = true;
        AudioTrack current = track;
        if (current == null) return;
        try { current.pause(); } catch (IllegalStateException ignored) {}
        try { current.flush(); } catch (IllegalStateException ignored) {}
        try { current.stop(); } catch (IllegalStateException ignored) {}
    }

    @Override public void close() {
        AudioTrack current = track;
        track = null;
        if (current == null) return;
        try { current.release(); } catch (RuntimeException ignored) {}
    }
}
