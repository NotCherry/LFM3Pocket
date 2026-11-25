package com.notcherry.lfm3pocket;

import java.time.ZonedDateTime;
import java.util.Locale;

/** LFM3-native tool card for voice-action turns (XML tool call or concise spoken reply). */
public final class VoiceToolPromptCard {
    private VoiceToolPromptCard() {}

    public static String onePassAudioPrompt(Locale locale) {
        return onePassAudioPrompt(locale, LfmToolCatalog.Capabilities.offlineDefaults());
    }

    public static String onePassAudioPrompt(Locale locale, LfmToolCatalog.Capabilities capabilities) {
        return "<|startoftext|><|im_start|>system\n"
                + LfmToolCatalog.voiceOnePassSystemPrompt(capabilities, locale) + "\n"
                + "<|im_end|>\n"
                + "<|im_start|>user\n"
                + LfmToolCatalog.turnContextLine(ZonedDateTime.now(), capabilities) + "\n"
                + "Listen to the attached audio. Answer the user or emit a <tool_call>.\n"
                + "<__media__><|im_end|>\n"
                + "<|im_start|>assistant\n";
    }

    /** Text-only voice-action prompt for STT-transcribed input (no media attachment). */
    public static String onePassTextPrompt(Locale locale, String userText) {
        return onePassTextPrompt(locale, userText, LfmToolCatalog.Capabilities.offlineDefaults());
    }

    public static String onePassTextPrompt(Locale locale, String userText, LfmToolCatalog.Capabilities capabilities) {
        String safeText = userText == null ? "" : userText.trim();
        return "<|startoftext|><|im_start|>system\n"
                + LfmToolCatalog.voiceOnePassSystemPrompt(capabilities, locale) + "\n"
                + "<|im_end|>\n"
                + "<|im_start|>user\n"
                + LfmToolCatalog.turnContextLine(ZonedDateTime.now(), capabilities) + "\n"
                + safeText + "<|im_end|>\n"
                + "<|im_start|>assistant\n";
    }

    /** Transcript-only confirm listen (Omni ASR pass on a short clip). */
    public static String confirmAsrPrompt() {
        return "<|startoftext|><|im_start|>system\nPerform ASR.\n<|im_end|>\n"
                + "<|im_start|>user\n<__media__><|im_end|>\n"
                + "<|im_start|>assistant\n";
    }

    public static boolean containsToolNames(String prompt) {
        return prompt != null && prompt.contains("set_timer") && prompt.contains("toggle_flashlight");
    }
}