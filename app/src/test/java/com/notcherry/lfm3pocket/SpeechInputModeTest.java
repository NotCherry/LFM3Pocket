package com.notcherry.lfm3pocket;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SpeechInputModeTest {
    @Test public void defaultsToOmniForUnknownValues() {
        assertEquals(SpeechInputMode.OMNI_AUDIO, SpeechInputMode.fromWire(null));
        assertEquals(SpeechInputMode.OMNI_AUDIO, SpeechInputMode.fromWire(""));
        assertEquals(SpeechInputMode.OMNI_AUDIO, SpeechInputMode.fromWire("bogus"));
    }

    @Test public void parsesKnownWireNames() {
        assertEquals(SpeechInputMode.OMNI_AUDIO, SpeechInputMode.fromWire("omni_audio"));
        assertEquals(SpeechInputMode.CACTUS_WHISTLE_STT, SpeechInputMode.fromWire("cactus_whistle_stt"));
        assertEquals(SpeechInputMode.CACTUS_WHISTLE_STT, SpeechInputMode.fromWire("CACTUS_WHISTLE_STT"));
    }
}
