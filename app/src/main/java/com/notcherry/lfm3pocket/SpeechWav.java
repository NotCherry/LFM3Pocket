package com.notcherry.lfm3pocket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Little-endian PCM16 WAV helpers for generated speech buffers. */
public final class SpeechWav {
    private SpeechWav() {}

    public static byte[] toWav(byte[] pcm16le, int sampleRateHz, int channels, int bitsPerSample) {
        if (pcm16le == null || pcm16le.length == 0) throw new IllegalArgumentException("pcm");
        if (sampleRateHz < 8000 || channels < 1 || bitsPerSample != 16 || (pcm16le.length % 2) != 0)
            throw new IllegalArgumentException("format");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(44 + pcm16le.length);
            int byteRate = sampleRateHz * channels * bitsPerSample / 8;
            int blockAlign = channels * bitsPerSample / 8;
            out.write("RIFF".getBytes("US-ASCII"));
            writeLe(out, 36 + pcm16le.length, 4);
            out.write("WAVEfmt ".getBytes("US-ASCII"));
            writeLe(out, 16, 4);
            writeLe(out, 1, 2);
            writeLe(out, channels, 2);
            writeLe(out, sampleRateHz, 4);
            writeLe(out, byteRate, 4);
            writeLe(out, blockAlign, 2);
            writeLe(out, bitsPerSample, 2);
            out.write("data".getBytes("US-ASCII"));
            writeLe(out, pcm16le.length, 4);
            out.write(pcm16le);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeLe(ByteArrayOutputStream out, long value, int bytes) {
        for (int i = 0; i < bytes; i++) out.write((int) ((value >> (8 * i)) & 255));
    }
}
