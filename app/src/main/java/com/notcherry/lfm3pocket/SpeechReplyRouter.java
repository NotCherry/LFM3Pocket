package com.notcherry.lfm3pocket;

/**
 * Small integration seam for optional English tiny-voca TTS (#voice epic / PR #149).
 * Omni generation stays the default when the setting is off.
 */
public final class SpeechReplyRouter {
    private final RuntimePreferences preferences;
    private final TinyVocaPackStore packStore;

    public SpeechReplyRouter(RuntimePreferences preferences, TinyVocaPackStore packStore) {
        this.preferences = preferences;
        this.packStore = packStore;
    }

    public SpeechReplyBackend select(String spokenText) {
        boolean enabled = preferences.tinyVocaEnglishTtsEnabled();
        boolean packReady = packStore.isReady();
        boolean phonemizerReady = false;
        if (enabled && packReady) {
            phonemizerReady = TinyVocaPhonemizerBridge.ensureLoaded(packStore.phonemizerDir());
        }
        return route(enabled, packReady, phonemizerReady, spokenText);
    }

    /** Unit-test seam without Android preferences or storage. */
    static SpeechReplyBackend route(boolean tinyVocaEnabled, boolean packReady,
            boolean phonemizerReady, String spokenText) {
        if (!tinyVocaEnabled) {
            return SpeechReplyBackend.OMNI;
        }
        EnglishSpeechHeuristics.LikelyLanguage language =
                EnglishSpeechHeuristics.classify(spokenText);
        if (language != EnglishSpeechHeuristics.LikelyLanguage.ENGLISH) {
            return SpeechReplyBackend.OMNI;
        }
        if (packReady && phonemizerReady) {
            return SpeechReplyBackend.TINY_VOCA;
        }
        return SpeechReplyBackend.SYSTEM_TTS;
    }
}