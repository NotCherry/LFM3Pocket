package com.notcherry.lfm3pocket;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SpeechInputProvidersTest {
    @Test public void omniModeUsesOmniProvider() {
        SttProvider provider = SpeechInputProviders.forMode(SpeechInputMode.OMNI_AUDIO);
        assertEquals(SpeechInputMode.OMNI_AUDIO, provider.mode());
        assertTrue(provider instanceof OmniSpeechInputProvider);
    }

    @Test public void cactusModeUsesCactusProvider() {
        SttProvider provider = SpeechInputProviders.forMode(SpeechInputMode.CACTUS_WHISTLE_STT);
        assertEquals(SpeechInputMode.CACTUS_WHISTLE_STT, provider.mode());
        assertTrue(provider instanceof CactusWhistleSttProvider);
    }
}
