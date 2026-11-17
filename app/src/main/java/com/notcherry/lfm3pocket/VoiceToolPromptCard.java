package com.notcherry.lfm3pocket;

import java.util.Locale;

/** Short tool card for LFM3-Omni voice-action turns (one strict JSON object, no prose scrape). */
public final class VoiceToolPromptCard {
    private static final String TOOL_CARD =
            "You may emit exactly one JSON tool object from this allowlist, or reply in the user's language.\n"
                    + "Tools: toggle_flashlight, set_volume, set_timer, set_alarm, show_alarms, open_application, "
                    + "open_settings, copy_text, share_text, current_time, calculator, unit_convert, weather.\n"
                    + "Example: {\"tool\":\"set_timer\",\"arguments\":{\"seconds\":300,\"label\":\"Tea\"}}";

    private VoiceToolPromptCard() {}

    public static String onePassAudioPrompt(Locale locale) {
        String languageHint = locale != null && "pl".equals(locale.getLanguage())
                ? "Reply in Polish when appropriate."
                : "Reply in English when appropriate.";
        return "<|startoftext|><|im_start|>system\n"
                + "You are Hanami, a concise on-device assistant. "
                + TOOL_CARD + "\n"
                + languageHint + " If you need an Android action, emit exactly one JSON tool object and no other prose.\n"
                + "<|im_end|>\n"
                + "<|im_start|>user\nListen to the attached audio. Answer the user or emit one tool JSON object.\n"
                + "<__media__><|im_end|>\n"
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