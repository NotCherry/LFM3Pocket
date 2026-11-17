package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class VoiceConfirmLexiconTest {
    @Test public void polishAndEnglishApprove() {
        assertEquals(VoiceConfirmLexicon.Decision.APPROVE, VoiceConfirmLexicon.matchTranscript("tak"));
        assertEquals(VoiceConfirmLexicon.Decision.APPROVE, VoiceConfirmLexicon.matchTranscript("TAK."));
        assertEquals(VoiceConfirmLexicon.Decision.APPROVE, VoiceConfirmLexicon.matchTranscript("potwierdzam"));
        assertEquals(VoiceConfirmLexicon.Decision.APPROVE, VoiceConfirmLexicon.matchTranscript("yes"));
    }

    @Test public void polishAndEnglishReject() {
        assertEquals(VoiceConfirmLexicon.Decision.REJECT, VoiceConfirmLexicon.matchTranscript("nie"));
        assertEquals(VoiceConfirmLexicon.Decision.REJECT, VoiceConfirmLexicon.matchTranscript("anuluj"));
        assertEquals(VoiceConfirmLexicon.Decision.REJECT, VoiceConfirmLexicon.matchTranscript("no"));
    }

    @Test public void garbageIsAmbiguous() {
        assertEquals(VoiceConfirmLexicon.Decision.AMBIGUOUS, VoiceConfirmLexicon.matchTranscript(""));
        assertEquals(VoiceConfirmLexicon.Decision.AMBIGUOUS, VoiceConfirmLexicon.matchTranscript("maybe"));
        assertEquals(VoiceConfirmLexicon.Decision.AMBIGUOUS, VoiceConfirmLexicon.matchTranscript("tak nie"));
    }
}