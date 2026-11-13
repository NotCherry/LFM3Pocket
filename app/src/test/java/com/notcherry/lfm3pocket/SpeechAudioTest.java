package com.notcherry.lfm3pocket;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class SpeechAudioTest {
    @Test public void fixtureToneWritesReadablePcm16MonoWav() throws Exception {
        File dir = Files.createTempDirectory("speech-audio").toFile();
        try {
            File wav = SpeechAudio.fixtureTone(dir, 250);
            assertTrue(wav.isFile());
            assertTrue(wav.getName().startsWith("spoken-"));
            short[] pcm = SpeechAudio.readPcm(wav);
            assertEquals(SpeechAudio.SAMPLE_RATE / 4, pcm.length);
            boolean nonzero = false;
            for (short sample : pcm) if (sample != 0) { nonzero = true; break; }
            assertTrue(nonzero);
            File roundTrip = new File(dir, "roundtrip.wav");
            SpeechAudio.writeWav(roundTrip, pcm);
            assertArrayEquals(pcm, SpeechAudio.readPcm(roundTrip));
        } finally {
            File[] files = dir.listFiles();
            if (files != null) for (File file : files) file.delete();
            dir.delete();
        }
    }
}
