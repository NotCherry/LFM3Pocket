package com.notcherry.lfm3pocket;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CactusWhistleSttProviderTest {
    @Test public void reads16kMonoPcm16WavAsFloatSamples() throws Exception {
        File wav = File.createTempFile("cactus-wav-", ".wav");
        try {
            writePcm16Wav(wav, 16_000, new short[] { 0, 16384, -16384, 32767, -32768 });
            float[] samples = CactusWhistleSttProvider.readWav16kMonoFloat(wav);
            assertEquals(5, samples.length);
            assertEquals(0f, samples[0], 0.0001f);
            assertEquals(0.5f, samples[1], 0.001f);
            assertEquals(-0.5f, samples[2], 0.001f);
            assertTrue(samples[3] > 0.99f);
            assertTrue(samples[4] <= -0.99f);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            wav.delete();
        }
    }

    @Test public void rejectsNon16kMonoWav() throws Exception {
        File wav = File.createTempFile("cactus-wav-bad-", ".wav");
        try {
            writePcm16Wav(wav, 8_000, new short[] { 1, 2, 3, 4 });
            IllegalArgumentException error = null;
            try {
                CactusWhistleSttProvider.readWav16kMonoFloat(wav);
            } catch (IllegalArgumentException e) {
                error = e;
            }
            assertTrue(error != null && error.getMessage().contains("16 kHz mono"));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            wav.delete();
        }
    }

    @Test public void transcribeSurfacesMissingRecordingAsError() {
        SttResult result = new CactusWhistleSttProvider().transcribe(null, new File("/no/such/recording.wav"), new AtomicBoolean(false));
        // Without Android context/preferences this should fail safely, never throw.
        assertTrue(result.status == SttResult.Status.ERROR
                || result.status == SttResult.Status.UNAVAILABLE);
    }

    private static void writePcm16Wav(File file, int sampleRate, short[] samples) throws IOException {
        int dataBytes = samples.length * 2;
        try (FileOutputStream out = new FileOutputStream(file)) {
            writeAscii(out, "RIFF");
            writeLe(out, 36 + dataBytes, 4);
            writeAscii(out, "WAVEfmt ");
            writeLe(out, 16, 4); // fmt chunk size
            writeLe(out, 1, 2);  // PCM
            writeLe(out, 1, 2);  // mono
            writeLe(out, sampleRate, 4);
            writeLe(out, sampleRate * 2, 4); // byte rate
            writeLe(out, 2, 2);  // block align
            writeLe(out, 16, 2); // bits
            writeAscii(out, "data");
            writeLe(out, dataBytes, 4);
            for (short sample : samples) writeLe(out, sample & 0xFFFF, 2);
        }
    }

    private static void writeAscii(FileOutputStream out, String text) throws IOException {
        out.write(text.getBytes("US-ASCII"));
    }

    private static void writeLe(FileOutputStream out, long value, int bytes) throws IOException {
        for (int i = 0; i < bytes; i++) out.write((int) ((value >> (8 * i)) & 0xFF));
    }
}
