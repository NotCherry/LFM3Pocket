package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.Test;

public final class VoiceSilenceTrimTest {
    @Test public void trimsLeadingSilenceWithoutDroppingShortUtterance() {
        byte[] wav = sampleWav(new short[] { 0, 0, 0, 50, 60, 70, 0, 0 });
        byte[] trimmed = VoiceSilenceTrim.trimWav(wav, 40);
        assertTrue(trimmed.length <= wav.length);
        assertTrue(trimmed.length >= 44 + 8); // at least four samples kept
    }

    @Test public void keepsQuietClipWhenTrimWouldBeTooShort() {
        byte[] wav = sampleWav(new short[] { 5, 5, 8 });
        assertEquals(wav.length, VoiceSilenceTrim.trimWav(wav, 40).length);
    }

    @Test public void treatsPcmSamplesAsSignedLittleEndian() {
        byte[] wav = sampleWav(new short[] { 0, 0, 0, 200, 0, 0 });
        assertEquals(200, VoiceSilenceTrim.pcm16Sample(wav, 44, 3));
        assertEquals(-500, VoiceSilenceTrim.pcm16Sample(sampleWav(new short[] { -500 }), 44, 0));
    }

    private static byte[] sampleWav(short[] samples) {
        int pcmBytes = samples.length * 2;
        byte[] wav = new byte[44 + pcmBytes];
        System.arraycopy("RIFF".getBytes(), 0, wav, 0, 4);
        System.arraycopy("WAVEfmt ".getBytes(), 0, wav, 8, 8);
        System.arraycopy("data".getBytes(), 0, wav, 36, 4);
        ByteBuffer h = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(4, 36 + pcmBytes);
        h.putInt(40, pcmBytes);
        for (int i = 0; i < samples.length; i++) {
            int off = 44 + i * 2;
            wav[off] = (byte) samples[i];
            wav[off + 1] = (byte) (samples[i] >> 8);
        }
        return wav;
    }
}