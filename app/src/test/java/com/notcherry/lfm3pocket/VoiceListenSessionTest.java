package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public final class VoiceListenSessionTest {
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
     * Regression for the CI hang: cancel while the capture worker publishes RECORDING with a
     * synchronous UI dispatcher used to deadlock (VoiceListenSession ↔ RecordingController).
     */
    @Test(timeout = 10_000)
    public void cancelDuringRecordingPublishDoesNotDeadlock() throws Exception {
        File dir = Files.createTempDirectory("voice-listen-deadlock").toFile();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        java.util.concurrent.ScheduledExecutorService timeoutExec = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch reading = new CountDownLatch(1);
        AtomicBoolean releaseRead = new AtomicBoolean(false);
        RecordingController.Factory factory = () -> new RecordingController.Source() {
            @Override public void start() {}
            @Override public int read(short[] buffer) throws InterruptedException {
                reading.countDown();
                while (!releaseRead.get()) {
                    Thread.sleep(10);
                }
                return -1;
            }
            @Override public void close() {}
        };
        VoiceListenSession session = new VoiceListenSession(dir, factory, () -> true,
                worker, Runnable::run, timeoutExec,
                new VoiceListenSession.Listener() {
                    @Override public void changed(VoiceListenSession.State state, String message) {}
                    @Override public void wavReady(File wav) { if (wav != null) wav.delete(); }
                }, VoiceListenLimits.MAX_ACTION_SAMPLES);
        try {
            assertTrue(session.startTimed(30_000));
            assertTrue("capture never reached read()", reading.await(5, TimeUnit.SECONDS));
            // Give the worker time to publish RECORDING (sync UI) before cancel races it.
            Thread.sleep(50);
            session.cancel("deadlock-regression");
            assertFalse(session.state() == VoiceListenSession.State.LISTENING);
        } finally {
            releaseRead.set(true);
            session.close();
            worker.shutdownNow();
            timeoutExec.shutdownNow();
            worker.awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}