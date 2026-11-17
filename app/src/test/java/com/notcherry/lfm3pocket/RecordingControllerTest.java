package com.notcherry.lfm3pocket;

import org.junit.Test;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class RecordingControllerTest {
    private final Queue<Runnable> worker=new ArrayDeque<>();
    private final AtomicBoolean permission=new AtomicBoolean(true);
    private final AtomicInteger opened=new AtomicInteger(),released=new AtomicInteger();
    private final List<RecordingController.State> states=new ArrayList<>();
    private File complete;
    private RecordingController controller;
    private int samples=4096;
    private boolean revoke;
    private RecordingController create(File directory){
        controller=new RecordingController(directory,()->{
            opened.incrementAndGet();
            return new RecordingController.Source(){
                public void start(){}
                public int read(short[] buffer){
                    Arrays.fill(buffer,(short)0x1234);
                    if(revoke)permission.set(false);else controller.stop();
                    return samples;
                }
                public void close(){released.incrementAndGet();}
            };
        },permission::get,worker::add,Runnable::run,(state,message,file)->{states.add(state);if(file!=null)complete=file;});
        return controller;
    }
    @Test public void explicitStartWritesValidPrivatePcmWavAndReleasesSource()throws Exception{
        File dir=Files.createTempDirectory("recording").toFile();
        try{
            RecordingController c=create(dir);assertEquals(0,opened.get());assertEquals(RecordingController.State.IDLE,c.state());
            assertTrue(c.start());assertFalse(c.start());assertEquals(0,opened.get());worker.remove().run();
            assertEquals(1,opened.get());assertEquals(1,released.get());assertEquals(RecordingController.State.IDLE,c.state());
            assertTrue(states.contains(RecordingController.State.RECORDING));assertTrue(states.contains(RecordingController.State.STOPPING));
            byte[] wav=Files.readAllBytes(complete.toPath());assertEquals(44+8192,wav.length);
            assertEquals("RIFF",new String(wav,0,4,"US-ASCII"));assertEquals("WAVE",new String(wav,8,4,"US-ASCII"));
            assertEquals(0x34,wav[44]&255);assertEquals(0x12,wav[45]&255);
            assertEquals(16000,java.nio.ByteBuffer.wrap(wav,24,4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt());
        }finally{for(File f:dir.listFiles())f.delete();dir.delete();}
    }
    @Test public void deniedPermissionAndCloseBeforeQueuedStartNeverOpenMicrophone()throws Exception{
        File dir=Files.createTempDirectory("recording").toFile();
        try{
            RecordingController c=create(dir);permission.set(false);c.start();worker.remove().run();
            assertEquals(RecordingController.State.ERROR,c.state());assertEquals(0,opened.get());
            permission.set(true);c.start();c.close();worker.remove().run();
            assertEquals(0,opened.get());assertEquals(0,dir.list().length);assertFalse(c.start());
        }finally{dir.delete();}
    }
    @Test public void permissionRevocationDeletesPartialAndReleasesRecorder()throws Exception{
        File dir=Files.createTempDirectory("recording").toFile();
        try{
            RecordingController c=create(dir);revoke=true;c.start();worker.remove().run();
            assertEquals(RecordingController.State.ERROR,c.state());assertEquals(1,released.get());
            assertNull(complete);assertEquals(0,dir.list().length);
        }finally{dir.delete();}
    }
    @Test public void cancelThenStartWhileCaptureRunningDeliversNewWavWithoutStaleTurn() throws Exception {
        File dir = Files.createTempDirectory("recording-preempt").toFile();
        try {
            java.util.Queue<Runnable> queue = new java.util.ArrayDeque<>();
            java.util.concurrent.atomic.AtomicBoolean holdFirstRead = new java.util.concurrent.atomic.AtomicBoolean(true);
            java.util.concurrent.atomic.AtomicInteger openCount = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger wavDeliveries = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<File> lastWav = new java.util.concurrent.atomic.AtomicReference<>();
            RecordingController[] box = new RecordingController[1];
            box[0] = new RecordingController(dir, () -> {
                int opened = openCount.incrementAndGet();
                return new RecordingController.Source() {
                    @Override public void start() { }
                    @Override public int read(short[] buffer) throws Exception {
                        if (opened == 1) {
                            while (holdFirstRead.get()) Thread.sleep(2);
                            return 0;
                        }
                        java.util.Arrays.fill(buffer, (short) 0x5678);
                        box[0].stop();
                        return buffer.length;
                    }
                    @Override public void close() { }
                };
            }, () -> true, queue::add, Runnable::run,
                    (state, message, file) -> {
                        if (file != null) {
                            wavDeliveries.incrementAndGet();
                            lastWav.set(file);
                        }
                    });
            RecordingController c = box[0];
            assertTrue(c.start());
            assertEquals(1, queue.size());
            Thread blocked = new Thread(queue.poll());
            blocked.start();
            while (openCount.get() < 1) Thread.sleep(2);
            c.cancel();
            assertTrue(c.start());
            holdFirstRead.set(false);
            blocked.join(5_000);
            assertFalse(blocked.isAlive());
            assertEquals(1, queue.size());
            queue.remove().run();
            assertEquals(1, wavDeliveries.get());
            assertNotNull(lastWav.get());
            byte[] wav = Files.readAllBytes(lastWav.get().toPath());
            assertTrue(wav.length > 44);
            assertEquals((byte) 0x78, wav[44]);
            assertEquals((byte) 0x56, wav[45]);
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }

    @Test public void shortRecordingRecoversForAnotherExplicitAttempt()throws Exception{
        File dir=Files.createTempDirectory("recording").toFile();
        try{
            RecordingController c=create(dir);samples=10;c.start();worker.remove().run();
            assertEquals(RecordingController.State.ERROR,c.state());assertEquals(0,dir.list().length);
            samples=4096;assertTrue(c.start());worker.remove().run();assertNotNull(complete);assertEquals(2,released.get());
        }finally{for(File f:dir.listFiles())f.delete();dir.delete();}
    }
}
