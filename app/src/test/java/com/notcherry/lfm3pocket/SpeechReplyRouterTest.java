package com.notcherry.lfm3pocket;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SpeechReplyRouterTest {
    @Test
    public void defaultUsesOmni() {
        assertEquals(SpeechReplyBackend.OMNI,
                SpeechReplyRouter.route(false, true, true, "Hello there."));
    }

    @Test
    public void polishKeepsOmniWhenTinyVocaEnabled() {
        assertEquals(SpeechReplyBackend.OMNI,
                SpeechReplyRouter.route(true, true, true, "Cześć, to po polsku."));
    }

    @Test
    public void englishWithoutPackFallsBackToSystem() {
        assertEquals(SpeechReplyBackend.SYSTEM_TTS,
                SpeechReplyRouter.route(true, false, true, "Hello world."));
    }

    @Test
    public void englishWithPackAndPhonemizerUsesTinyVoca() {
        assertEquals(SpeechReplyBackend.TINY_VOCA,
                SpeechReplyRouter.route(true, true, true, "Hello world."));
    }
}