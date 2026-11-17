package com.notcherry.lfm3pocket;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class EnglishSpeechHeuristicsTest {
    @Test
    public void detectsPolishDiacritics() {
        assertEquals(EnglishSpeechHeuristics.LikelyLanguage.NON_ENGLISH,
                EnglishSpeechHeuristics.classify("To jest po polsku z ąę."));
    }

    @Test
    public void latinWithoutPolishCountsAsEnglish() {
        assertEquals(EnglishSpeechHeuristics.LikelyLanguage.ENGLISH,
                EnglishSpeechHeuristics.classify("Hello from Hanami."));
    }
}