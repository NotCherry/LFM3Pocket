package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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


    @Test public void failsWhenInvokedFromNonOwnerThread() throws Exception {
        ExecutorService other = Executors.newSingleThreadExecutor(r -> new Thread(r, "policy-off-owner"));
        try {
            Future<?> future = other.submit(() -> policy.onLoaded("x.gguf", 0L));
            try {
                future.get(5, TimeUnit.SECONDS);
                fail("expected IllegalStateException for non-owner thread");
            } catch (java.util.concurrent.ExecutionException e) {
                assertTrue("expected IllegalStateException but was " + e.getCause(),
                        e.getCause() instanceof IllegalStateException);
            }
        } finally {
            other.shutdownNow();
        }
    }

    @Test public void serializedInterleavingsKeepForegroundAndWakeEffectsConsistent() throws Exception {
        ExecutorService owner = Executors.newSingleThreadExecutor(r -> new Thread(r, "policy-owner"));
        ExecutorService producers = Executors.newFixedThreadPool(4);
        try {
            RuntimePolicy ownerPolicy = owner.submit(RuntimePolicy::new).get(5, TimeUnit.SECONDS);

            // Deterministic interleaving covering listen, confirmation(consent), playback/speech,
            // generation, unload/keep-loaded release, idle timeout, and memory pressure/thermal.
            List<Callable<RuntimePolicy.Effects>> sequence = Arrays.asList(
                    () -> ownerPolicy.onSettings(true, 15, false, 0L),
                    () -> ownerPolicy.onLoaded("lfm.gguf", 0L),
                    () -> ownerPolicy.onVoiceTalkAvailable(true),
                    () -> ownerPolicy.onListenStarted("Listening…"),
                    () -> ownerPolicy.onConsent("p1", "Do action", true), // confirmation shown while listening/session active
                    () -> ownerPolicy.onSpeechStarted(),                   // spoken confirm/speech synthesis
                    () -> ownerPolicy.onPlaybackStarted(),                 // playback
                    () -> ownerPolicy.onTrim(false, 1_000L),               // memory pressure while busy
                    () -> ownerPolicy.onSpeechFinished(2_000L),
                    () -> ownerPolicy.onConsentCleared(2_100L),
                    () -> ownerPolicy.onListenFinished(2_200L),
                    () -> ownerPolicy.onGenerationStarted("q", 64, 3_000L),
                    () -> ownerPolicy.onTokens(10, 3_500L),
                    () -> ownerPolicy.onThermal(3, 3_600L),                // severe heat while generating -> defer unload
                    () -> ownerPolicy.onTrim(false, 3_700L),               // pressure strike while generating
                    () -> ownerPolicy.onGenerationFinished(4_000L),        // unload-after-turn/heat takes effect
                    () -> ownerPolicy.onSettings(true, 0, false, 5_000L),
                    () -> ownerPolicy.onLoaded("lfm.gguf", 5_100L),
                    () -> ownerPolicy.onClock(20L * 60L * 1000L),          // idle timeout should not unload when idleMinutes==0
                    () -> ownerPolicy.onSettings(true, 15, false, 20L * 60L * 1000L),
                    () -> ownerPolicy.onClock(20L * 60L * 1000L + 15L * 60L * 1000L), // idle timeout unload
                    () -> ownerPolicy.onLoaded("lfm.gguf", 40L * 60L * 1000L),
                    () -> ownerPolicy.onGenerationStarted("q2", 32, 40L * 60L * 1000L),
                    () -> ownerPolicy.onTrim(true, 40L * 60L * 1000L + 1L), // complete trim cancels/unloads
                    () -> ownerPolicy.onSettings(false, 0, false, 41L * 60L * 1000L),
                    () -> ownerPolicy.onLoaded("lfm.gguf", 41L * 60L * 1000L),
                    () -> ownerPolicy.onKeepLoadedRelease(41L * 60L * 1000L + 1L),
                    () -> ownerPolicy.unloadRequested()
            );

            // Submit from multiple producer threads but execute in this interleaved order on the owner.
            // This simulates concurrent event sources whose mutations are serialized through one owner.
            CountDownLatch start = new CountDownLatch(1);
            @SuppressWarnings("unchecked")
            Future<RuntimePolicy.Effects>[] futures = new Future[sequence.size()];
            for (int i = 0; i < sequence.size(); i++) {
                final int idx = i;
                futures[i] = producers.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    // small stagger to encourage cross-thread submission while preserving deterministic order
                    if (idx % 3 == 0) Thread.yield();
                    return owner.submit(sequence.get(idx)).get(5, TimeUnit.SECONDS);
                });
            }
            start.countDown();

            List<RuntimePolicy.Effects> effects = new ArrayList<>();
            for (Future<RuntimePolicy.Effects> f : futures) {
                RuntimePolicy.Effects e = f.get(10, TimeUnit.SECONDS);
                assertNotNull(e);
                assertNotNull(e.notice);
                assertConsistentEffects(e);
                effects.add(e);
            }

            // Final unloadRequested/drop effect must be fully idle/hidden with no service or wake.
            RuntimePolicy.Effects last = effects.get(effects.size() - 1);
            assertTrue(last.unloadModel);
            assertFalse(last.wantService);
            assertFalse(last.wantWake);
            assertEquals(RuntimeNotice.Kind.HIDDEN, last.notice.kind);
        } finally {
            producers.shutdownNow();
            owner.shutdownNow();
        }
    }

    private static void assertConsistentEffects(RuntimePolicy.Effects e) {
        switch (e.notice.kind) {
            case HIDDEN:
                assertFalse("hidden notice must not want service", e.wantService);
                assertFalse("hidden notice must not want wake", e.wantWake);
                assertFalse("hidden notice must not capture mic", e.notice.microphoneCapture);
                assertFalse("hidden notice must not be media playback", e.notice.mediaPlayback);
                break;
            case LISTENING:
                assertTrue("listening must want service", e.wantService);
                assertTrue("listening must want wake", e.wantWake);
                assertTrue("listening must capture mic", e.notice.microphoneCapture);
                break;
            case GENERATING:
                assertTrue("generating must want service", e.wantService);
                assertTrue("generating must want wake", e.wantWake);
                break;
            case SPEAKING:
                assertTrue("speaking must want service", e.wantService);
                assertTrue("speaking must want wake", e.wantWake);
                break;
            case APPROVAL:
            case LOADING:
            case RESIDENT:
                assertTrue(e.notice.kind + " must want service", e.wantService);
                // Wake depends on generating/synthesizing/playing/listening; approval/loading/resident
                // notices may be shown while those wake-requiring states are active.
                if (!e.wantWake) {
                    assertFalse("unexpected media playback without wake", e.notice.mediaPlayback);
                }
                break;
            default:
                fail("unexpected notice kind " + e.notice.kind);
        }
        if (e.unloadModel && e.notice.kind == RuntimeNotice.Kind.HIDDEN) {
            assertFalse("unload drop must not want service", e.wantService);
            assertFalse("unload drop must not want wake", e.wantWake);
        }
    }

}
