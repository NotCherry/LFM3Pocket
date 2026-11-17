package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RuntimePolicyTest {
    private final RuntimePolicy policy = new RuntimePolicy();

    @Test public void listeningUsesMicrophoneCaptureFlag() {
        policy.onLoaded("lfm.gguf", 0L);
        RuntimePolicy.Effects effects = policy.onListenStarted("Listening…");
        assertEquals(RuntimeNotice.Kind.LISTENING, effects.notice.kind);
        assertTrue(effects.notice.microphoneCapture);
        RuntimePolicy.Effects done = policy.onListenFinished(1_000L);
        assertEquals(RuntimeNotice.Kind.RESIDENT, done.notice.kind);
        assertFalse(done.notice.microphoneCapture);
    }

    @Test public void residentHidesTalkWithoutAudioIn() {
        policy.onLoaded("text.gguf", 1_000L);
        RuntimePolicy.Effects effects = policy.onVoiceTalkAvailable(false);
        assertEquals(2, effects.notice.actions.length);
        assertEquals(RuntimeNotice.Action.ASK, effects.notice.actions[0]);
        assertEquals(RuntimeNotice.Action.UNLOAD, effects.notice.actions[1]);
    }

    @Test public void residentNoticeOffersUnloadAndOpen() {
        policy.onLoaded("lfm.gguf", 1_000L);
        RuntimePolicy.Effects effects = policy.onVoiceTalkAvailable(true);
        assertTrue(effects.wantService);
        assertFalse(effects.wantWake);
        assertFalse(effects.unloadModel);
        assertEquals(RuntimeNotice.Kind.RESIDENT, effects.notice.kind);
        assertEquals("Model loaded", effects.notice.title);
        assertTrue(effects.notice.text.startsWith("Model loaded · using memory"));
        assertTrue(effects.notice.text.contains("lfm.gguf"));
        assertEquals(RuntimeNotice.Action.TALK, effects.notice.actions[0]);
        assertEquals(RuntimeNotice.Action.ASK, effects.notice.actions[1]);
        assertEquals(RuntimeNotice.Action.UNLOAD, effects.notice.actions[2]);
        assertEquals(3, effects.notice.actions.length);
        assertFalse(effects.notice.publicLockScreen);
    }

    @Test public void generationShowsTokensRateAndStopWithoutUnloading() {
        policy.onLoaded("lfm.gguf", 0L);
        RuntimePolicy.Effects started = policy.onGenerationStarted("What is the tide?", 128, 1_000L);
        assertTrue(started.wantWake);
        assertEquals(RuntimeNotice.Kind.GENERATING, started.notice.kind);
        assertEquals(RuntimeNotice.Action.STOP, started.notice.actions[0]);
        assertEquals(RuntimeNotice.Action.OPEN, started.notice.actions[1]);
        assertTrue(started.notice.text.contains("What is the tide?"));
        RuntimePolicy.Effects tokens = policy.onTokens(40, 2_200L);
        assertTrue(tokens.notice.text.contains("40 / 128 tokens"));
        assertTrue(tokens.notice.text.contains("tok/s"));
        assertEquals(40, tokens.notice.progress);
        assertEquals(128, tokens.notice.progressMax);
        RuntimePolicy.Effects stopped = policy.stopRequested(3_000L);
        assertTrue(stopped.cancelTurn);
        assertFalse(stopped.unloadModel);
        assertTrue(policy.modelLoaded());
        assertEquals(RuntimeNotice.Kind.RESIDENT, stopped.notice.kind);
    }

    @Test public void unloadDropsTheServiceAndTheWeights() {
        policy.onLoaded("lfm.gguf", 0L);
        RuntimePolicy.Effects effects = policy.unloadRequested();
        assertTrue(effects.unloadModel);
        assertFalse(effects.wantService);
        assertEquals(RuntimeNotice.Kind.HIDDEN, effects.notice.kind);
        assertFalse(policy.modelLoaded());
    }

    @Test public void idleTimerUnloadsOnlyWhenEnabled() {
        policy.onSettings(true, 0, false, 0L);
        policy.onLoaded("lfm.gguf", 0L);
        policy.onGenerationStarted("q", 32, 0L);
        RuntimePolicy.Effects done = policy.onGenerationFinished(1_000L);
        assertFalse(done.unloadModel);
        assertEquals(RuntimeNotice.Kind.RESIDENT, policy.onClock(60L * 60L * 1000L).notice.kind);

        policy.onSettings(true, 15, false, 0L);
        policy.onGenerationStarted("q", 32, 5_000L);
        policy.onGenerationFinished(10_000L);
        RuntimePolicy.Effects early = policy.onClock(10_000L + 14L * 60L * 1000L);
        assertFalse(early.unloadModel);
        RuntimePolicy.Effects due = policy.onClock(10_000L + 15L * 60L * 1000L);
        assertTrue(due.unloadModel);
        assertFalse(policy.modelLoaded());
    }

    @Test public void lockPreviewIsOffUntilTheSettingSaysOtherwise() {
        policy.onLoaded("lfm.gguf", 0L);
        policy.onGenerationStarted("secret", 8, 0L);
        assertFalse(policy.onTokens(1, 1_000L).notice.publicLockScreen);
        RuntimePolicy.Effects shown = policy.onSettings(true, 0, true, 1_000L);
        assertTrue(shown.notice.publicLockScreen);
    }

    @Test public void thermalLadderWarnsThenUnloads() {
        policy.onLoaded("lfm.gguf", 0L);
        policy.onGenerationStarted("q", 64, 0L);
        RuntimePolicy.Effects warm = policy.onThermal(2, 1_000L);
        assertTrue(warm.notice.text.contains("Phone is warm"));
        assertFalse(warm.unloadModel);
        assertFalse(policy.refuseNewTurns());
        RuntimePolicy.Effects severe = policy.onThermal(3, 2_000L);
        assertTrue(policy.refuseNewTurns());
        assertFalse(severe.unloadModel);
        assertTrue(severe.notice.text.contains("very warm"));
        RuntimePolicy.Effects finished = policy.onGenerationFinished(3_000L);
        assertTrue(finished.unloadModel);
    }

    @Test public void criticalHeatCancelsAndUnloadsImmediately() {
        policy.onLoaded("lfm.gguf", 0L);
        policy.onGenerationStarted("q", 64, 0L);
        RuntimePolicy.Effects effects = policy.onThermal(4, 1_000L);
        assertTrue(effects.cancelTurn);
        assertTrue(effects.unloadModel);
        assertFalse(policy.modelLoaded());
    }

    @Test public void lowBatteryWarnsWithoutUnloading() {
        policy.onLoaded("lfm.gguf", 0L);
        RuntimePolicy.Effects effects = policy.onBatteryLow(true);
        assertTrue(effects.notice.text.contains("Battery low"));
        assertFalse(effects.unloadModel);
        assertTrue(policy.modelLoaded());
    }

    @Test public void trimCriticalUnloadsAnIdleModelAndWaitsOnceWhileGenerating() {
        policy.onLoaded("lfm.gguf", 0L);
        assertTrue(policy.onTrim(false, 0L).unloadModel);

        policy.onLoaded("lfm.gguf", 0L);
        policy.onGenerationStarted("q", 32, 0L);
        RuntimePolicy.Effects first = policy.onTrim(false, 1_000L);
        assertFalse(first.unloadModel);
        assertTrue(policy.modelLoaded());
        RuntimePolicy.Effects second = policy.onTrim(false, 2_000L);
        assertTrue(second.cancelTurn);
        assertTrue(second.unloadModel);
    }

    @Test public void trimCompleteCancelsImmediately() {
        policy.onLoaded("lfm.gguf", 0L);
        policy.onGenerationStarted("q", 8, 0L);
        RuntimePolicy.Effects effects = policy.onTrim(true, 0L);
        assertTrue(effects.cancelTurn);
        assertTrue(effects.unloadModel);
    }

    @Test public void safeShortConsentCanBeApprovedFromTheShade() {
        policy.onLoaded("lfm.gguf", 0L);
        RuntimePolicy.Effects effects = policy.onConsent("id-1", "Alarm\nhour: 7", false);
        assertEquals(RuntimeNotice.Kind.APPROVAL, effects.notice.kind);
        assertEquals(RuntimeNotice.Action.REVIEW, effects.notice.actions[0]);
        RuntimePolicy.Effects safe = policy.onConsent("id-2", "Flashlight\non: true", true);
        assertEquals(RuntimeNotice.Action.APPROVE, safe.notice.actions[0]);
        assertEquals(RuntimeNotice.Action.REJECT, safe.notice.actions[1]);
        assertEquals("id-2", safe.notice.proposalId);
        RuntimePolicy.Effects cleared = policy.onConsentCleared(10L);
        assertEquals(RuntimeNotice.Kind.RESIDENT, cleared.notice.kind);
    }

    @Test public void keepLoadedOffReleaseUnloads() {
        policy.onSettings(true, 0, false, 0L);
        policy.onLoaded("lfm.gguf", 0L);
        assertFalse(policy.onKeepLoadedRelease(1L).unloadModel);
        policy.onSettings(false, 0, false, 1L);
        assertTrue(policy.onKeepLoadedRelease(2L).unloadModel);
    }

    @Test public void speakingUsesStopAndMediaTypeOnlyWhilePlaying() {
        policy.onLoaded("lfm.gguf", 0L);
        RuntimePolicy.Effects synth = policy.onSpeechStarted();
        assertEquals(RuntimeNotice.Kind.SPEAKING, synth.notice.kind);
        assertTrue(synth.wantWake);
        assertFalse(synth.notice.mediaPlayback);
        assertTrue(synth.notice.text.contains("Generating the spoken reply"));
        RuntimePolicy.Effects playing = policy.onPlaybackStarted();
        assertTrue(playing.notice.mediaPlayback);
        assertTrue(playing.notice.text.contains("Playing the spoken reply"));
        RuntimePolicy.Effects done = policy.onSpeechFinished(5_000L);
        assertEquals(RuntimeNotice.Kind.RESIDENT, done.notice.kind);
        assertFalse(done.unloadModel);
    }
}
