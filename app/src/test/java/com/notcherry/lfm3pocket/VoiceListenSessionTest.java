package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.Test;

public final class VoiceListenSessionTest extends HanamiConcurrencyUnitTest {
    @Test public void idleToListeningToReady() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "voice-listen");
        dir.mkdirs();
        LinkedBlockingQueue<Runnable> workerTasks = new LinkedBlockingQueue<>();
        List<VoiceListenSession.State> states = new ArrayList<>();
        AtomicInteger samples = new AtomicInteger(4096);
        RecordingController.Factory factory = () -> new RecordingController.Source() {
            public void start() {}
            public int read(short[] buffer) {
                java.util.Arrays.fill(buffer, (short) 0x1234);
                return samples.get();
            }
            public void close() {}
        };
        java.util.concurrent.ScheduledExecutorService timeoutExec = Executors.newSingleThreadScheduledExecutor();
        VoiceListenSession session = new VoiceListenSession(dir, factory, () -> true,
                workerTasks::offer, Runnable::run, timeoutExec,
                new VoiceListenSession.Listener() {
                    @Override public void changed(VoiceListenSession.State state, String message) {
                        states.add(state);
                    }
                    @Override public void wavReady(File wav) {
                        assertTrue(wav.isFile());
                        wav.delete();
                    }
                }, VoiceListenLimits.MAX_ACTION_SAMPLES);
        try {
            assertTrue(session.startPtt());
            assertEquals(VoiceListenSession.State.LISTENING, session.state());
            workerTasks.take().run();
            session.stopPtt();
            while (!workerTasks.isEmpty()) workerTasks.poll().run();
            assertTrue(states.contains(VoiceListenSession.State.READY));
        } finally {
            session.close();
            timeoutExec.shutdownNow();
        }
    }

    /**
     * Regression for the CI hang: cancel while the capture worker is inside the RECORDING
     * publish UI callback. On the pre-fix lock order this deadlocks (session holds
     * VoiceListenSession, waits for RecordingController; worker holds RecordingController
     * across sync ui.accept). Blocks inside the RECORDING ui.accept — not after read().
     */
    @Test(timeout = 10_000)
    public void cancelDuringRecordingPublishDoesNotDeadlock() throws Exception {
        File dir = Files.createTempDirectory("voice-listen-deadlock").toFile();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService canceler = Executors.newSingleThreadExecutor();
        java.util.concurrent.ScheduledExecutorService timeoutExec = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch inRecordingPublish = new CountDownLatch(1);
        CountDownLatch allowRecordingPublish = new CountDownLatch(1);
        AtomicInteger uiPublishes = new AtomicInteger();
        AtomicBoolean sourceClosed = new AtomicBoolean(false);
        AtomicBoolean releaseRead = new AtomicBoolean(false);
        List<String> cancelMessages = new ArrayList<>();

        // STARTING is publish #1; RECORDING is #2. Block only inside RECORDING's ui.accept
        // so cancel races while the old code would still hold the controller monitor.
        Consumer<Runnable> ui = task -> {
            int n = uiPublishes.incrementAndGet();
            if (n == 2) {
                inRecordingPublish.countDown();
                try {
                    if (!allowRecordingPublish.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting to resume RECORDING publish");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            task.run();
        };

        RecordingController.Factory factory = () -> new RecordingController.Source() {
            @Override public void start() {}
            @Override public int read(short[] buffer) throws InterruptedException {
                while (!releaseRead.get()) {
                    Thread.sleep(10);
                }
                return 0;
            }
            @Override public void close() { sourceClosed.set(true); }
        };

        VoiceListenSession session = new VoiceListenSession(dir, factory, () -> true,
                worker, ui, timeoutExec,
                new VoiceListenSession.Listener() {
                    @Override public void changed(VoiceListenSession.State state, String message) {
                        if (state == VoiceListenSession.State.CANCELLED) {
                            cancelMessages.add(message);
                        }
                    }
                    @Override public void wavReady(File wav) { if (wav != null) wav.delete(); }
                }, VoiceListenLimits.MAX_ACTION_SAMPLES);
        try {
            assertTrue(session.startTimed(30_000));
            assertTrue("never entered RECORDING publish", inRecordingPublish.await(5, TimeUnit.SECONDS));

            Future<?> cancelFuture = canceler.submit(() -> session.cancel("deadlock-regression"));
            // Let cancel contend with the blocked RECORDING publish, then release the publish.
            Thread.sleep(50);
            allowRecordingPublish.countDown();
            cancelFuture.get(5, TimeUnit.SECONDS);

            assertEquals(VoiceListenSession.State.CANCELLED, session.state());
            assertTrue(cancelMessages.contains("deadlock-regression"));
            releaseRead.set(true);
            // Give capture a moment to exit try-with-resources and close the source.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!sourceClosed.get() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue("microphone Source.close() was not called", sourceClosed.get());
        } finally {
            releaseRead.set(true);
            allowRecordingPublish.countDown();
            session.close();
            worker.shutdownNow();
            canceler.shutdownNow();
            timeoutExec.shutdownNow();
            worker.awaitTermination(2, TimeUnit.SECONDS);
            canceler.awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}
