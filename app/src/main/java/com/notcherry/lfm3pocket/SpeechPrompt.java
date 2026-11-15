package com.notcherry.lfm3pocket;

/** Builds LFM3-Omni TTS chat prompts without depending on llama.cpp common templates. */
public final class SpeechPrompt {
    private SpeechPrompt() {}

    /** Prefer plain prose for Omni TTS; markdown markers often get spoken or confuse the voice model. */
    public static String spokenText(String reply) {
        String text = ThinkingSanitizer.clean(reply).answer.trim();
        if (text.isEmpty()) return "";
        text = text.replace('\u00A0', ' ');
        text = text.replaceAll("(?m)^\\s{0,3}#{1,6}\\s+", "");
        text = text.replaceAll("(?m)^\\s*[-*+]\\s+", "");
        text = text.replaceAll("(?m)^\\s*\\d+\\.\\s+", "");
        text = text.replaceAll("[*_`]+", "");
        text = text.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
        return text;
    }

    public static String tts(String text, String language, String voice) {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("text");
        String lang = language == null || language.trim().isEmpty() ? "en" : language.trim();
        String spokenVoice = voice == null || voice.trim().isEmpty() ? "female" : voice.trim();
        // LFM3-Omni TTS chat template: empty think block then forced audio modality marker.
        // mtmd_helper_gen_audio requires the prompt to end on <|audio_start|>.
        return "<|startoftext|><|im_start|>system\n"
                + "Perform TTS in " + languageLabel(lang) + " using a " + spokenVoice + " voice."
                + "<|im_end|>\n<|im_start|>user\n"
                + text.trim()
                + "<|im_end|>\n<|im_start|>assistant\n"
                + "<think></think><|audio_start|>";
    }

    private static String languageLabel(String language) {
        switch (language.toLowerCase(java.util.Locale.ROOT)) {
            case "pl": case "polish": return "Polish";
            case "en": case "en-us": case "en-gb": case "english": return "American English";
            default: return language;
        }
    }
}
