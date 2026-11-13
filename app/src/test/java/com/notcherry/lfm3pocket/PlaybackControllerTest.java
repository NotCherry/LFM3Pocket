package com.notcherry.lfm3pocket;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class PlaybackControllerTest {
    private final Queue<Runnable> worker=new ArrayDeque<>();
    private final List<PlaybackController.State> states=new ArrayList<>();
    private final AtomicInteger opened=new AtomicInteger(),written=new AtomicInteger(),released=new AtomicInteger();
    private final AtomicBoolean focusGranted=new AtomicBoolean(true);
    private final AtomicInteger focusRequests=new AtomicInteger(),focusAbandons=new AtomicInteger();
    private volatile boolean writeFails;
    private PlaybackController controller;

    private PlaybackController create(){
        controller=new PlaybackController(() -> {
            opened.incrementAndGet();
            return new PlaybackController.Track(){
                public void start(int sampleRateHz,int channels,int bitsPerSample){
                    if(sampleRateHz<=0||channels!=1||bitsPerSample!=16)
                        throw new IllegalArgumentException("bad format");
                }
                public void write(byte[] pcm){
                    if(writeFails)throw new IllegalStateException("write failed");
                    written.addAndGet(pcm.length);
                }
                public void stop(){}
                public void close(){released.incrementAndGet();}
            };
        }, () -> {
            focusRequests.incrementAndGet();
            return focusGranted.get();
        }, focusAbandons::incrementAndGet, worker::add, Runnable::run,
                (state,message)->states.add(state));
        return controller;
    }

    private void drain(){while(!worker.isEmpty())worker.remove().run();}

    @Test public void playsPcmThenReleasesTrackAndFocus(){
        PlaybackController c=create();
        byte[] pcm=new byte[]{1,0,2,0,3,0,4,0};
        assertTrue(c.play(pcm,24000,1,16));
        assertFalse(c.play(pcm,24000,1,16));
        assertEquals(0,opened.get());
        drain();
        assertEquals(1,opened.get());
        assertEquals(1,focusRequests.get());
        assertEquals(8,written.get());
        assertEquals(1,released.get());
        assertEquals(1,focusAbandons.get());
        assertEquals(PlaybackController.State.IDLE,c.state());
        assertTrue(states.contains(PlaybackController.State.STARTING));
        assertTrue(states.contains(PlaybackController.State.PLAYING));
        assertFalse("Successful completion skips STOPPING; that state is for explicit stop()",
                states.contains(PlaybackController.State.STOPPING));
    }

    @Test public void stopCancelsQueuedPlaybackBeforeTrackOpens(){
        PlaybackController c=create();
        assertTrue(c.play(new byte[]{1,0},24000,1,16));
        c.stop();
        drain();
        assertEquals(0,opened.get());
        assertEquals(PlaybackController.State.IDLE,c.state());
        assertEquals(0,focusRequests.get());
    }

    @Test public void focusDenialAndWriteFailureSurfaceErrorAndRelease(){
        PlaybackController c=create();
        focusGranted.set(false);
        assertTrue(c.play(new byte[]{1,0},24000,1,16));
        drain();
        assertEquals(PlaybackController.State.ERROR,c.state());
        assertEquals(0,opened.get());

        focusGranted.set(true);writeFails=true;
        assertTrue(c.play(new byte[]{1,0,2,0},24000,1,16));
        drain();
        assertEquals(PlaybackController.State.ERROR,c.state());
        assertEquals(1,opened.get());
        assertEquals(1,released.get());
        assertEquals(1,focusAbandons.get());
    }

    @Test public void closeSuppressesStaleCompletion(){
        PlaybackController c=create();
        assertTrue(c.play(new byte[]{9,0},16000,1,16));
        c.close();
        drain();
        assertEquals(0,opened.get());
        assertEquals(PlaybackController.State.CLOSED,c.state());
        assertFalse(c.play(new byte[]{1,0},16000,1,16));
    }

    @Test public void emptyPcmRejectedWithoutOpeningTrack(){
        PlaybackController c=create();
        assertFalse(c.play(new byte[0],24000,1,16));
        assertFalse(c.play(null,24000,1,16));
        assertEquals(0,opened.get());
        assertEquals(PlaybackController.State.IDLE,c.state());
    }
}
