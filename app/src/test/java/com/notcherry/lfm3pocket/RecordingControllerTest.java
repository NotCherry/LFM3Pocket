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
    @Test public void shortRecordingRecoversForAnotherExplicitAttempt()throws Exception{
        File dir=Files.createTempDirectory("recording").toFile();
        try{
            RecordingController c=create(dir);samples=10;c.start();worker.remove().run();
            assertEquals(RecordingController.State.ERROR,c.state());assertEquals(0,dir.list().length);
            samples=4096;assertTrue(c.start());worker.remove().run();assertNotNull(complete);assertEquals(2,released.get());
        }finally{for(File f:dir.listFiles())f.delete();dir.delete();}
    }
}
