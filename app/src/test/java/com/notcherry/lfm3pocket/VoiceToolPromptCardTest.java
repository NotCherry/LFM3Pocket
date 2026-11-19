package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertTrue;

import java.util.Locale;

import org.junit.Test;

public final class VoiceToolPromptCardTest {
    @Test public void promptListsToolsAndMediaMarker() {
        String prompt = VoiceToolPromptCard.onePassAudioPrompt(Locale.US);
        assertTrue(prompt.contains("<__media__>"));
        assertTrue(VoiceToolPromptCard.containsToolNames(prompt));
    }

    @Test public void textPromptIncludesUserTextWithoutAudioMarker() {
        String prompt = VoiceToolPromptCard.onePassTextPrompt(Locale.US, "set a timer for tea");
        assertTrue(prompt.contains("set a timer for tea"));
        assertTrue(!prompt.contains("<__media__>"));
        assertTrue(VoiceToolPromptCard.containsToolNames(prompt));
    }
}