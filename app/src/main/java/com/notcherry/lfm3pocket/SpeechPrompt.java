package com.notcherry.lfm3pocket;

/** Builds LFM3-Omni TTS chat prompts without depending on llama.cpp common templates. */
public final class SpeechPrompt {
    private SpeechPrompt() {}

    public static String tts(String text, String language, String voice) {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("text");
        String lang = language == null || language.trim().isEmpty() ? "en" : language.trim();
        String spokenVoice = voice == null || voice.trim().isEmpty() ? "female" : voice.trim();
        return "<|startoftext|><|im_start|>system\n"
                + "Perform TTS in " + languageLabel(lang) + " using a " + spokenVoice + " voice.\n"
                + "<|im_end|>\n<|im_start|>user\n"
                + text.trim()
                + "<|im_end|>\n<|im_start|>assistant\n";
    }

    private static String languageLabel(String language) {
        switch (language.toLowerCase(java.util.Locale.ROOT)) {
            case "pl": case "polish": return "Polish";
            case "en": case "en-us": case "en-gb": case "english": return "American English";
            default: return language;
        }
    }
}
