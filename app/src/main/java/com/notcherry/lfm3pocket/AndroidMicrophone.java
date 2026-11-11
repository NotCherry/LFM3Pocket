package com.notcherry.lfm3pocket;

import android.media.*;
import java.io.IOException;

/** Thread-confined AudioRecord adapter; non-blocking reads make cancellation independent of audio delivery. */
final class AndroidMicrophone implements RecordingController.Source {
    private final AudioRecord recorder;
    AndroidMicrophone()throws IOException{
        int minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(minimum<=0)throw new IOException("This device cannot record 16 kHz microphone audio");
        recorder=new AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum,8192));
        if(recorder.getState()!=AudioRecord.STATE_INITIALIZED){recorder.release();throw new IOException("Microphone initialization failed");}
    }
    public void start()throws IOException{
        recorder.startRecording();
        if(recorder.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IOException("Microphone did not start");
    }
    public int read(short[] samples){return recorder.read(samples,0,samples.length,AudioRecord.READ_NON_BLOCKING);}
    public void close(){try{recorder.stop();}catch(IllegalStateException ignored){}finally{recorder.release();}}
}
