package com.notcherry.lfm3pocket;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.Executor;
import java.util.function.*;

/** Owns one explicitly requested, bounded private PCM recording. Microphone operations stay on its worker. */
public final class RecordingController implements AutoCloseable {
    public enum State { IDLE, STARTING, RECORDING, STOPPING, ERROR, CLOSED }
    public interface Source extends AutoCloseable {
        void start() throws Exception;
        /** Non-blocking mono PCM16 samples at 16 kHz. */
        int read(short[] samples) throws Exception;
        void close();
    }
    public interface Factory { Source open() throws Exception; }
    public interface Listener { void changed(State state,String message,File completed); }
    private static final int RATE=16000, MIN_SAMPLES=RATE/4, MAX_SAMPLES=RATE*60;
    private final File directory;private final Factory factory;private final BooleanSupplier permission;
    private final Executor worker;private final Consumer<Runnable> ui;private final Listener listener;
    private volatile State state=State.IDLE;
    private volatile boolean stopping,discard,closed;
    private boolean active;private long epoch;
    public RecordingController(File directory,Factory factory,BooleanSupplier permission,Executor worker,
                               Consumer<Runnable> ui,Listener listener){
        this.directory=directory;this.factory=factory;this.permission=permission;this.worker=worker;this.ui=ui;this.listener=listener;
    }
    public State state(){return state;}
    public synchronized boolean start(){
        if(closed || active)return false;
        active=true;stopping=false;discard=false;long turn=++epoch;
        publish(turn,State.STARTING,"Starting microphone...",null);
        try{worker.execute(()->capture(turn));}
        catch(RuntimeException e){finish(turn,State.ERROR,"Could not schedule recording",null);}
        return true;
    }
    public synchronized void stop(){
        if(!active || closed || stopping)return;
        stopping=true;publish(epoch,State.STOPPING,"Finishing voice recording...",null);
    }
    public synchronized void cancel(){
        if(!active || closed)return;
        discard=true;stopping=true;publish(epoch,State.STOPPING,"Cancelling voice recording...",null);
    }
    @Override public synchronized void close(){closed=true;discard=true;stopping=true;state=State.CLOSED;}
    private synchronized void publish(long turn,State next,String message,File file){
        if(closed || turn!=epoch){if(file!=null)file.delete();return;}
        state=next;
        ui.accept(()->{
            synchronized(this){if(closed || turn!=epoch){if(file!=null)file.delete();return;}}
            listener.changed(next,message,file);
        });
    }
    private synchronized void finish(long turn,State next,String message,File file){
        active=false;publish(turn,next,message,file);
    }
    private void capture(long turn){
        File partial=null,complete=null;
        try{
            if(stopping){finish(turn,State.IDLE,"Recording cancelled",null);return;}
            if(!permission.getAsBoolean())throw new SecurityException("Microphone permission is unavailable");
            if(!directory.isDirectory() && !directory.mkdirs())throw new IOException("Cannot create private recording directory");
            partial=File.createTempFile("voice-",".partial",directory);
            long count=0;
            try(Source source=factory.open();RandomAccessFile output=new RandomAccessFile(partial,"rw")){
                output.write(new byte[44]);
                if(stopping){finish(turn,State.IDLE,"Recording cancelled",null);return;}
                if(!permission.getAsBoolean())throw new SecurityException("Microphone permission was revoked");
                source.start();
                if(!stopping)publish(turn,State.RECORDING,"Recording voice input. Tap Stop recording when finished.",null);
                short[] samples=new short[4096];byte[] pcm=new byte[samples.length*2];
                long deadline=System.nanoTime()+60_000_000_000L;
                while(!stopping){
                    if(!permission.getAsBoolean())throw new SecurityException("Microphone permission was revoked");
                    int n=source.read(samples);
                    if(n<0 || n>samples.length)throw new IOException("Microphone read failed: "+n);
                    if(n==0)Thread.sleep(10);
                    else{
                        n=(int)Math.min(n,MAX_SAMPLES-count);
                        for(int i=0;i<n;i++){pcm[i*2]=(byte)samples[i];pcm[i*2+1]=(byte)(samples[i]>>8);}
                        output.write(pcm,0,n*2);count+=n;
                    }
                    if(count>=MAX_SAMPLES || System.nanoTime()>=deadline)stop();
                }
                if(discard){finish(turn,State.IDLE,"Recording cancelled",null);return;}
                if(!permission.getAsBoolean())throw new SecurityException("Microphone permission was revoked");
                if(count<MIN_SAMPLES)throw new IOException("Recording was too short; try again");
                writeHeader(output,count*2);output.getFD().sync();
            }
            if(discard){finish(turn,State.IDLE,"Recording cancelled",null);return;}
            complete=new File(directory,partial.getName().replace(".partial",".wav"));
            Files.move(partial.toPath(),complete.toPath(),StandardCopyOption.ATOMIC_MOVE);
            finish(turn,State.IDLE,"Voice recording attached. Add a question and press Send.",complete);
            complete=null; // Listener owns this completed private file.
        }catch(Exception e){
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
            finish(turn,State.ERROR,"Voice recording failed: "+e.getMessage(),null);
        }finally{if(partial!=null)partial.delete();if(complete!=null)complete.delete();}
    }
    private static void writeHeader(RandomAccessFile out,long bytes)throws IOException{
        out.seek(0);out.writeBytes("RIFF");le(out,bytes+36,4);out.writeBytes("WAVEfmt ");le(out,16,4);
        le(out,1,2);le(out,1,2);le(out,RATE,4);le(out,RATE*2,4);le(out,2,2);le(out,16,2);
        out.writeBytes("data");le(out,bytes,4);
    }
    private static void le(RandomAccessFile out,long value,int bytes)throws IOException{
        for(int i=0;i<bytes;i++)out.write((int)(value>>(8*i))&255);
    }
}
