package com.notcherry.lfm3pocket;

import org.junit.Test;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Buffer-contract tests for local Omni speech generation (V04). No GGUF or JNI. */
public class SpeechGenerationContractTest {
    static final class CapturingSink implements InferenceEngine.AudioSink {
        Integer sampleRate;Integer channels;Integer bits;
        final ByteArrayOutputStream pcm=new ByteArrayOutputStream();
        int formatCalls;boolean complete;
        public void onAudioFormat(int sampleRateHz,int channelCount,int bitsPerSample){
            formatCalls++;sampleRate=sampleRateHz;channels=channelCount;bits=bitsPerSample;
        }
        public void onAudioPcm(byte[] interleavedLittleEndianPcm){
            try{pcm.write(interleavedLittleEndianPcm);}catch(IOException e){throw new AssertionError(e);}
        }
        public void onAudioComplete(){complete=true;}
    }

    static final class FakeSpeechEngine implements InferenceEngine {
        volatile boolean stopped;volatile String lastPrompt;volatile SpeechOptions lastOptions;
        byte[] pcm=tonePcm(24000,400); // 400 samples ≈ 16.7 ms at 24 kHz
        String unsupported;
        public long loadModel(String m,String p){return 1;}
        public void unloadModel(long h){}
        public String generate(long h,String p,String media,int max,TokenSink sink){return "text path unused";}
        public void stop(){stopped=true;}
        public void resetStop(){stopped=false;}
        @Override public String generateSpeech(long handle,String prompt,SpeechOptions options,AudioSink sink){
            lastPrompt=prompt;lastOptions=options;
            if(unsupported!=null)return unsupported;
            if(prompt==null || sink==null)return "Missing prompt or audio callback";
            if(stopped)return null;
            sink.onAudioFormat(24000,1,16);
            int frames=Math.min(options==null?512:options.maxFrames,pcm.length/2);
            byte[] chunk=Arrays.copyOf(pcm,frames*2);
            sink.onAudioPcm(chunk);
            if(stopped)return null;
            sink.onAudioComplete();
            return chunk.length==0?"Speech generation produced no audio":null;
        }
    }

    private static byte[] tonePcm(int rate,int samples){
        byte[] pcm=new byte[samples*2];
        for(int i=0;i<samples;i++){
            short v=(short)(Math.sin(2*Math.PI*440*i/(double)rate)*12000);
            pcm[i*2]=(byte)v;pcm[i*2+1]=(byte)(v>>8);
        }
        return pcm;
    }

    @Test public void fakeSpeechEngineStreamsNonemptyPcm16MonoAt24kHz(){
        FakeSpeechEngine engine=new FakeSpeechEngine();CapturingSink sink=new CapturingSink();
        assertNull(engine.generateSpeech(1,SpeechPrompt.tts("Hello from the pocket assistant.","en","female"),
                SpeechOptions.DEFAULT,sink));
        assertEquals(1,sink.formatCalls);
        assertEquals(Integer.valueOf(24000),sink.sampleRate);
        assertEquals(Integer.valueOf(1),sink.channels);
        assertEquals(Integer.valueOf(16),sink.bits);
        assertTrue(sink.pcm.size()>=2);
        assertTrue((sink.pcm.size()%2)==0);
        assertTrue(sink.complete);
        byte[] wav=SpeechWav.toWav(sink.pcm.toByteArray(),24000,1,16);
        assertEquals("RIFF",new String(wav,0,4));
        assertEquals("WAVE",new String(wav,8,4));
        assertEquals(24000,ByteBuffer.wrap(wav,24,4).order(ByteOrder.LITTLE_ENDIAN).getInt());
        assertEquals(1,ByteBuffer.wrap(wav,22,2).order(ByteOrder.LITTLE_ENDIAN).getShort());
        assertEquals(16,ByteBuffer.wrap(wav,34,2).order(ByteOrder.LITTLE_ENDIAN).getShort());
    }

    @Test public void unsupportedEngineReturnsClearErrorWithoutAudioCallbacks(){
        FakeSpeechEngine engine=new FakeSpeechEngine();
        engine.unsupported="Speech generation is not available on this model";
        CapturingSink sink=new CapturingSink();
        assertEquals("Speech generation is not available on this model",
                engine.generateSpeech(1,SpeechPrompt.tts("Hi","en","female"),SpeechOptions.DEFAULT,sink));
        assertEquals(0,sink.formatCalls);
        assertEquals(0,sink.pcm.size());
        assertFalse(sink.complete);
    }

    @Test public void stopBeforeSpeechSkipsAudioAndDefaultEngineRejectsClearly(){
        FakeSpeechEngine engine=new FakeSpeechEngine();engine.stop();
        CapturingSink sink=new CapturingSink();
        assertNull(engine.generateSpeech(1,SpeechPrompt.tts("Hi","pl","male"),SpeechOptions.DEFAULT,sink));
        assertEquals(0,sink.pcm.size());

        InferenceEngine missing=new InferenceEngine(){
            public long loadModel(String m,String p){return 0;}
            public void unloadModel(long h){}
            public String generate(long h,String p,String media,int max,TokenSink sink){return null;}
            public void stop(){}
            public void resetStop(){}
        };
        assertEquals("Speech generation is not available",
                missing.generateSpeech(1,"prompt",SpeechOptions.DEFAULT,sink));
    }

    @Test public void speechPromptEncodesLanguageAndVoiceForLocalTts(){
        String prompt=SpeechPrompt.tts("The harbor was quiet.","pl","male");
        assertTrue(prompt.contains("Perform TTS"));
        assertTrue(prompt.contains("Polish") || prompt.contains("pl"));
        assertTrue(prompt.toLowerCase(Locale.ROOT).contains("male"));
        assertTrue(prompt.contains("The harbor was quiet."));
        assertTrue(prompt.contains("<|im_start|>assistant"));
    }

    @Test public void speechWavRejectsEmptyPcm(){
        try{
            SpeechWav.toWav(new byte[0],24000,1,16);
            fail("empty pcm must fail");
        }catch(IllegalArgumentException expected){}
    }
}
