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
    private final int maxSamples;
    private final File directory;private final Factory factory;private final BooleanSupplier permission;
    private final Executor worker;private final Consumer<Runnable> ui;private final Listener listener;
    private volatile State state=State.IDLE;
    private volatile boolean stopping,discard,closed;
    private boolean active;private long epoch;
    /** Monotonic publish id: async UI may drop superseded events; only the latest seq is delivered. */
    private long publishSeq;
    public RecordingController(File directory,Factory factory,BooleanSupplier permission,Executor worker,
                               Consumer<Runnable> ui,Listener listener){
        this(directory,factory,permission,worker,ui,listener,MAX_SAMPLES);
    }
    public RecordingController(File directory,Factory factory,BooleanSupplier permission,Executor worker,
                               Consumer<Runnable> ui,Listener listener,int maxSamples){
        this.directory=directory;this.factory=factory;this.permission=permission;this.worker=worker;this.ui=ui;
        this.listener=listener;
        this.maxSamples=maxSamples<=0?MAX_SAMPLES:maxSamples;
    }
    public State state(){return state;}
    public boolean start(){
        long turn;
        synchronized(this){
            if(closed || active)return false;
            active=true;stopping=false;discard=false;turn=++epoch;
        }
        publish(turn,State.STARTING,"Starting microphone...",null);
        try{worker.execute(()->capture(turn));}
        catch(RuntimeException e){finish(turn,State.ERROR,"Could not schedule recording",null);}
        return true;
    }
    public void stop(){
        long turn;
        synchronized(this){
            if(!active || closed || stopping)return;
            stopping=true;turn=epoch;
        }
        publish(turn,State.STOPPING,"Finishing voice recording...",null);
    }
    public void cancel(){
        long turn;
        synchronized(this){
            if(!active || closed)return;
            discard=true;stopping=true;turn=epoch;
        }
        finish(turn,State.IDLE,"Recording cancelled",null);
    }
    @Override public synchronized void close(){closed=true;discard=true;stopping=true;state=State.CLOSED;}
    /**
     * Publish state without holding {@code this} across the UI/listener callback.
     * Sync UI dispatch ({@code Runnable::run} in unit tests) must not invert locks with
     * {@link VoiceListenSession}, which re-enters from the listener.
     * A publish sequence number drops superseded async UI events so STOPPING cannot
     * overtake a later IDLE/ERROR after {@link #finish}.
     */
    private void publish(long turn,State next,String message,File file){
        final long seq;
        synchronized(this){
            if(closed || turn!=epoch){if(file!=null)file.delete();return;}
            // stop() may race finish(): reject STOPPING once the turn is no longer active.
            if(next==State.STOPPING && !active)return;
            state=next;
            seq=++publishSeq;
        }
        ui.accept(()->{
            synchronized(this){
                if(closed || turn!=epoch || seq!=publishSeq){if(file!=null)file.delete();return;}
            }
            listener.changed(next,message,file);
        });
    }
    private void finish(long turn,State next,String message,File file){
        synchronized(this){active=false;}
        publish(turn,next,message,file);
    }
    private synchronized boolean superseded(long turn){return turn!=epoch;}

    private void capture(long turn){
        File partial=null,complete=null;
        try{
            if(superseded(turn))return;
            if(stopping){finishIfCurrent(turn,State.IDLE,"Recording cancelled",null);return;}
            if(!permission.getAsBoolean())throw new SecurityException("Microphone permission is unavailable");
            if(!directory.isDirectory() && !directory.mkdirs())throw new IOException("Cannot create private recording directory");
            partial=File.createTempFile("voice-",".partial",directory);
            long count=0;
            try(Source source=factory.open();RandomAccessFile output=new RandomAccessFile(partial,"rw")){
                output.write(new byte[44]);
                if(superseded(turn))return;
                if(stopping){finishIfCurrent(turn,State.IDLE,"Recording cancelled",null);return;}
                if(!permission.getAsBoolean())throw new SecurityException("Microphone permission was revoked");
                source.start();
                if(!stopping && !superseded(turn)) {
                    publish(turn,State.RECORDING,"Recording voice input. Tap Stop recording when finished.",null);
                }
                short[] samples=new short[4096];byte[] pcm=new byte[samples.length*2];
                long deadline=System.nanoTime()+((long)maxSamples*1_000_000_000L)/RATE;
                while(!stopping){
                    if(superseded(turn))return;
                    if(!permission.getAsBoolean())throw new SecurityException("Microphone permission was revoked");
                    int n=source.read(samples);
                    if(superseded(turn))return;
                    if(n<0 || n>samples.length)throw new IOException("Microphone read failed: "+n);
                    if(n==0)Thread.sleep(10);
                    else{
                        n=(int)Math.min(n,maxSamples-count);
                        for(int i=0;i<n;i++){pcm[i*2]=(byte)samples[i];pcm[i*2+1]=(byte)(samples[i]>>8);}
                        output.write(pcm,0,n*2);count+=n;
                    }
                    if(count>=maxSamples || System.nanoTime()>=deadline)stop();
                }
                if(superseded(turn))return;
                if(discard){finishIfCurrent(turn,State.IDLE,"Recording cancelled",null);return;}
                if(!permission.getAsBoolean())throw new SecurityException("Microphone permission was revoked");
                if(count<MIN_SAMPLES)throw new IOException("Recording was too short; try again");
                writeHeader(output,count*2);output.getFD().sync();
            }
            if(superseded(turn))return;
            if(discard){finishIfCurrent(turn,State.IDLE,"Recording cancelled",null);return;}
            complete=new File(directory,partial.getName().replace(".partial",".wav"));
            Files.move(partial.toPath(),complete.toPath(),StandardCopyOption.ATOMIC_MOVE);
            finishIfCurrent(turn,State.IDLE,"Voice recording attached. Add a question and press Send.",complete);
            complete=null; // Listener owns this completed private file.
        }catch(Exception e){
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
            if(!superseded(turn)) finishIfCurrent(turn,State.ERROR,"Voice recording failed: "+e.getMessage(),null);
        }finally{if(partial!=null)partial.delete();if(complete!=null)complete.delete();}
    }

    private void finishIfCurrent(long turn,State next,String message,File file){
        if(superseded(turn))return;
        finish(turn,next,message,file);
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
