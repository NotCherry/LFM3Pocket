package com.notcherry.lfm3pocket;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** PCM16 mono WAV helpers for spoken responses and fixture tones. */
public final class SpeechAudio {
    public static final int SAMPLE_RATE = 16000;

    private SpeechAudio() {}

    /** Writes a short sine tone as PCM16 mono WAV for tests and unsupported-speech fixtures. */
    public static File fixtureTone(File directory, int durationMs) throws IOException {
        if (directory == null) throw new IllegalArgumentException("directory");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create speech directory");
        int samples = Math.max(1, (int) ((SAMPLE_RATE * (long) Math.max(1, durationMs)) / 1000L));
        short[] pcm = new short[samples];
        double step = 2.0 * Math.PI * 440.0 / SAMPLE_RATE;
        for (int i = 0; i < samples; i++) pcm[i] = (short) (Math.sin(step * i) * 12000);
        File file = File.createTempFile("spoken-", ".wav", directory);
        try {
            writeWav(file, pcm);
            return file;
        } catch (IOException e) {
            file.delete();
            throw e;
        }
    }

    public static void writeWav(File file, short[] pcm) throws IOException {
        if (file == null || pcm == null) throw new IllegalArgumentException("file and pcm are required");
        long dataBytes = pcm.length * 2L;
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            out.setLength(0);
            out.writeBytes("RIFF");
            writeLe(out, dataBytes + 36, 4);
            out.writeBytes("WAVEfmt ");
            writeLe(out, 16, 4);
            writeLe(out, 1, 2); // PCM
            writeLe(out, 1, 2); // mono
            writeLe(out, SAMPLE_RATE, 4);
            writeLe(out, SAMPLE_RATE * 2L, 4);
            writeLe(out, 2, 2);
            writeLe(out, 16, 2);
            out.writeBytes("data");
            writeLe(out, dataBytes, 4);
            byte[] frame = new byte[2];
            for (short sample : pcm) {
                frame[0] = (byte) sample;
                frame[1] = (byte) (sample >> 8);
                out.write(frame);
            }
            out.getFD().sync();
        }
    }

    public static short[] readPcm(File wav) throws IOException {
        if (wav == null || !wav.isFile()) throw new IOException("WAV file is missing");
        byte[] raw = readAll(wav);
        if (raw.length < 44) throw new IOException("WAV is truncated");
        if (!ascii(raw, 0, 4).equals("RIFF") || !ascii(raw, 8, 4).equals("WAVE"))
            throw new IOException("Not a RIFF/WAVE file");
        int offset = 12;
        int channels = 0, rate = 0, bits = 0, dataOffset = -1, dataBytes = 0;
        while (offset + 8 <= raw.length) {
            String id = ascii(raw, offset, 4);
            int size = ByteBuffer.wrap(raw, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            int body = offset + 8;
            if ("fmt ".equals(id)) {
                if (size < 16 || body + 16 > raw.length) throw new IOException("Invalid fmt chunk");
                int format = ByteBuffer.wrap(raw, body, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
                channels = ByteBuffer.wrap(raw, body + 2, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
                rate = ByteBuffer.wrap(raw, body + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                bits = ByteBuffer.wrap(raw, body + 14, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
                if (format != 1) throw new IOException("Only PCM WAV is supported");
            } else if ("data".equals(id)) {
                dataOffset = body;
                dataBytes = size;
                break;
            }
            offset = body + size + (size & 1);
        }
        if (dataOffset < 0) throw new IOException("WAV has no data chunk");
        if (channels != 1 || rate != SAMPLE_RATE || bits != 16)
            throw new IOException("Expected PCM16 mono at " + SAMPLE_RATE + " Hz");
        if (dataOffset + dataBytes > raw.length) throw new IOException("WAV data is truncated");
        int samples = dataBytes / 2;
        short[] pcm = new short[samples];
        ByteBuffer buffer = ByteBuffer.wrap(raw, dataOffset, dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++) pcm[i] = buffer.getShort();
        return pcm;
    }

    private static void writeLe(RandomAccessFile out, long value, int bytes) throws IOException {
        for (int i = 0; i < bytes; i++) out.write((int) ((value >> (8 * i)) & 255));
    }

    private static String ascii(byte[] raw, int offset, int length) {
        return new String(raw, offset, length, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static byte[] readAll(File file) throws IOException {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }
}
