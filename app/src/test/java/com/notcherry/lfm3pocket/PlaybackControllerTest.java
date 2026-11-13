package com.notcherry.lfm3pocket;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class PlaybackControllerTest {
    private final Queue<Runnable> worker = new ArrayDeque<>();
    private final List<PlaybackController.State> states = new ArrayList<>();
    private final AtomicInteger opened = new AtomicInteger(), released = new AtomicInteger();
    private PlaybackController controller;
    private boolean failOpen;

    private PlaybackController create() {
        controller = new PlaybackController(() -> {
            opened.incrementAndGet();
            if (failOpen) throw new IllegalStateException("player unavailable");
            return new PlaybackController.Player() {
                public void start(short[] pcm) {}
                public int write(short[] pcm, int offset, int count) {
                    controller.stop();
                    return count;
                }
                public void stop() {}
                public void close() { released.incrementAndGet(); }
            };
        }, worker::add, Runnable::run, (state, message) -> states.add(state));
        return controller;
    }

    @Test public void playStreamsPrivateWavAndReleasesPlayer() throws Exception {
        File dir = Files.createTempDirectory("playback").toFile();
        try {
            File wav = SpeechAudio.fixtureTone(dir, 100);
            PlaybackController c = create();
            assertEquals(PlaybackController.State.IDLE, c.state());
            assertTrue(c.play(wav));
            assertFalse(c.play(wav));
            assertEquals(0, opened.get());
            worker.remove().run();
            assertEquals(1, opened.get());
            assertEquals(1, released.get());
            assertEquals(PlaybackController.State.IDLE, c.state());
            assertTrue(states.contains(PlaybackController.State.STARTING));
            assertTrue(states.contains(PlaybackController.State.PLAYING));
            assertTrue(states.contains(PlaybackController.State.STOPPING));
        } finally {
            File[] files = dir.listFiles();
            if (files != null) for (File file : files) file.delete();
            dir.delete();
        }
    }

    @Test public void openFailureReportsErrorWithoutStrandingState() throws Exception {
        File dir = Files.createTempDirectory("playback-error").toFile();
        try {
            File wav = SpeechAudio.fixtureTone(dir, 50);
            PlaybackController c = create();
            failOpen = true;
            assertTrue(c.play(wav));
            worker.remove().run();
            assertEquals(PlaybackController.State.ERROR, c.state());
            assertEquals(1, opened.get());
            assertEquals(0, released.get());
            failOpen = false;
            assertTrue(c.play(wav));
            worker.remove().run();
            assertEquals(PlaybackController.State.IDLE, c.state());
            assertEquals(1, released.get());
        } finally {
            File[] files = dir.listFiles();
            if (files != null) for (File file : files) file.delete();
            dir.delete();
        }
    }

    @Test public void closeBeforeQueuedStartNeverOpensPlayer() throws Exception {
        File dir = Files.createTempDirectory("playback-close").toFile();
        try {
            File wav = SpeechAudio.fixtureTone(dir, 50);
            PlaybackController c = create();
            assertTrue(c.play(wav));
            c.close();
            worker.remove().run();
            assertEquals(0, opened.get());
            assertEquals(PlaybackController.State.CLOSED, c.state());
            assertFalse(c.play(wav));
        } finally {
            File[] files = dir.listFiles();
            if (files != null) for (File file : files) file.delete();
            dir.delete();
        }
    }
}
