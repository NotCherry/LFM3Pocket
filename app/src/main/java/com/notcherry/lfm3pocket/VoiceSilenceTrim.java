package com.notcherry.lfm3pocket;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Trims leading and trailing silence from 16 kHz mono PCM16 WAV bytes (header-inclusive). */
public final class VoiceSilenceTrim {
    private static final int RATE = 16_000;
  /** Do not trim slices shorter than this after trimming (keeps short Polish words). */
    private static final int MIN_PCM_BYTES = RATE / 4 * 2;

    private VoiceSilenceTrim() {}

    /** @return trimmed WAV bytes, or the original file bytes when trim would be too aggressive */
    public static byte[] trimWav(byte[] wav, int energyThreshold) {
        if (wav == null || wav.length <= 44) return wav;
        ByteBuffer buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (!"RIFF".equals(readAscii(buf, 0, 4)) || !"WAVE".equals(readAscii(buf, 8, 4))) return wav;
        int dataOffset = findDataChunk(wav);
        if (dataOffset < 0) return wav;
        int dataLen = wav.length - dataOffset;
        if (dataLen < MIN_PCM_BYTES) return wav;
        int first = firstLoudSample(wav, dataOffset, dataLen, energyThreshold);
        int last = lastLoudSample(wav, dataOffset, dataLen, energyThreshold);
        if (first < 0 || last < first) return wav;
        int trimmedLen = (last - first + 1) * 2;
        if (trimmedLen < MIN_PCM_BYTES) return wav;
        byte[] out = new byte[44 + trimmedLen];
        System.arraycopy(wav, 0, out, 0, 44);
        System.arraycopy(wav, dataOffset + first * 2, out, 44, trimmedLen);
        patchHeader(out, trimmedLen);
        return out;
    }

    private static void patchHeader(byte[] wav, int pcmBytes) {
        ByteBuffer h = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(4, 36 + pcmBytes);
        h.putInt(40, pcmBytes);
    }

    private static int findDataChunk(byte[] wav) {
        int pos = 12;
        while (pos + 8 <= wav.length) {
            String id = readAscii(wav, pos, 4);
            int size = ByteBuffer.wrap(wav, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if ("data".equals(id)) return pos + 8;
            pos += 8 + size;
        }
        return -1;
    }

    private static int firstLoudSample(byte[] wav, int dataOffset, int dataLen, int threshold) {
        int samples = dataLen / 2;
        for (int i = 0; i < samples; i++) {
            int sample = pcm16At(wav, dataOffset, i);
            if (Math.abs(sample) >= threshold) return i;
        }
        return -1;
    }

    private static int lastLoudSample(byte[] wav, int dataOffset, int dataLen, int threshold) {
        int samples = dataLen / 2;
        for (int i = samples - 1; i >= 0; i--) {
            int sample = pcm16At(wav, dataOffset, i);
            if (Math.abs(sample) >= threshold) return i;
        }
        return -1;
    }

    /** Exposed for JVM tests verifying little-endian PCM sign extension. */
    static int pcm16Sample(byte[] wav, int dataOffset, int index) {
        return pcm16At(wav, dataOffset, index);
    }

    private static int pcm16At(byte[] wav, int dataOffset, int index) {
        int off = dataOffset + index * 2;
        return (wav[off] & 0xFF) | (wav[off + 1] << 8);
    }

    private static String readAscii(ByteBuffer buf, int offset, int len) {
        byte[] b = new byte[len];
        buf.position(offset);
        buf.get(b);
        return new String(b, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static String readAscii(byte[] wav, int offset, int len) {
        return new String(wav, offset, len, java.nio.charset.StandardCharsets.US_ASCII);
    }
}