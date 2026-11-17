package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
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
        VoiceListenSession session = new VoiceListenSession(dir, factory, () -> true,
                workerTasks::offer, Runnable::run, Executors.newSingleThreadScheduledExecutor(),
                new VoiceListenSession.Listener() {
                    @Override public void changed(VoiceListenSession.State state, String message) {
                        states.add(state);
                    }
                    @Override public void wavReady(File wav) {
                        assertTrue(wav.isFile());
                        wav.delete();
                    }
                }, VoiceListenLimits.MAX_ACTION_SAMPLES);
        assertTrue(session.startPtt());
        assertEquals(VoiceListenSession.State.LISTENING, session.state());
        workerTasks.take().run();
        session.stopPtt();
        while (!workerTasks.isEmpty()) workerTasks.poll().run();
        assertTrue(states.contains(VoiceListenSession.State.READY));
        session.close();
    }
}