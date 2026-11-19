package com.notcherry.lfm3pocket;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional on-device STT provider backed by the Cactus runtime and Whistle model. */
public final class CactusWhistleSttProvider implements SttProvider {
    @Override public SpeechInputMode mode() { return SpeechInputMode.CACTUS_WHISTLE_STT; }

    @Override public String displayName(Context context) {
        return context == null ? "Cactus Whistle STT" : context.getString(R.string.speech_input_cactus_whistle);
    }

    @Override public SttAvailability availability(Context context) {
        String modelPath = new RuntimePreferences(context).cactusWhistleModelPath();
        SttAvailability nativeAvail = CactusWhistleBridge.availability(modelPath);
        if (!nativeAvail.available) return nativeAvail;
        return SttAvailability.ready();
    }

    @Override public SttResult transcribe(Context context, File wavFile, AtomicBoolean cancelled) {
        if (cancelled != null && cancelled.get()) return SttResult.cancelled();
        if (context == null) {
            return SttResult.unavailable("Cactus Whistle STT is unavailable");
        }
        if (wavFile == null || !wavFile.isFile()) {
            return SttResult.error("Voice recording is unavailable");
        }
        String modelPath = new RuntimePreferences(context).cactusWhistleModelPath();
        SttAvailability avail = CactusWhistleBridge.availability(modelPath);
        if (!avail.available) return SttResult.unavailable(avail.message);
        try {
            float[] pcm = readWav16kMonoFloat(wavFile);
            if (cancelled != null && cancelled.get()) return SttResult.cancelled();
            if (pcm.length == 0) return SttResult.empty("No speech detected");
            return CactusWhistleBridge.transcribe(modelPath, pcm, cancelled);
        } catch (IOException e) {
            return SttResult.error("Could not read voice recording: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return SttResult.error(e.getMessage());
        } catch (Throwable t) {
            return SttResult.error("Cactus transcription failed: " + t.getMessage());
        }
    }

    /** Decodes a 16 kHz mono PCM16 WAV into normalized float samples in [-1, 1]. */
    static float[] readWav16kMonoFloat(File wavFile) throws IOException {
        byte[] bytes = readAll(wavFile);
        if (bytes.length <= 44) throw new IllegalArgumentException("Voice recording is empty");
        if (!"RIFF".equals(ascii(bytes, 0, 4)) || !"WAVE".equals(ascii(bytes, 8, 4))) {
            throw new IllegalArgumentException("Voice recording is not a WAV file");
        }
        int fmtOffset = -1;
        int dataOffset = -1;
        int dataSize = 0;
        int pos = 12;
        while (pos + 8 <= bytes.length) {
            String id = ascii(bytes, pos, 4);
            int size = leInt(bytes, pos + 4);
            if (pos + 8 + size > bytes.length) break;
            if ("fmt ".equals(id)) fmtOffset = pos + 8;
            if ("data".equals(id)) {
                dataOffset = pos + 8;
                dataSize = size;
                break;
            }
            pos += 8 + size + (size & 1);
        }
        if (fmtOffset < 0 || dataOffset < 0) throw new IllegalArgumentException("Voice recording is incomplete");
        int audioFormat = leShort(bytes, fmtOffset);
        int channels = leShort(bytes, fmtOffset + 2);
        int sampleRate = leInt(bytes, fmtOffset + 4);
        int bitsPerSample = leShort(bytes, fmtOffset + 14);
        if (audioFormat != 1 || channels != 1 || sampleRate != 16_000 || bitsPerSample != 16) {
            throw new IllegalArgumentException("Cactus Whistle expects 16 kHz mono PCM16 audio");
        }
        int samples = Math.min(dataSize, bytes.length - dataOffset) / 2;
        float[] out = new float[Math.max(0, samples)];
        ByteBuffer buf = ByteBuffer.wrap(bytes, dataOffset, samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++) {
            short s = buf.getShort();
            out[i] = Math.max(-1f, Math.min(1f, s / 32768f));
        }
        return out;
    }

    private static byte[] readAll(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.max(0, file.length())];
            int offset = 0;
            while (offset < data.length) {
                int read = in.read(data, offset, data.length - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset == data.length) return data;
            byte[] out = new byte[offset];
            System.arraycopy(data, 0, out, 0, offset);
            return out;
        }
    }

    private static String ascii(byte[] bytes, int offset, int len) {
        return new String(bytes, offset, len, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int leInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static int leShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }
}
