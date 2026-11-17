package com.notcherry.lfm3pocket;

/** Raised when the user stops speech while local synthesis is in progress. */
public final class SpeechSynthesisCancelled extends Exception {
    public SpeechSynthesisCancelled() {
        super("Spoken response cancelled.");
    }
}