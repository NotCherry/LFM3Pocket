package com.notcherry.lfm3pocket;

import android.content.Context;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.core.app.ActivityScenario;
import org.json.JSONObject;
import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

/** Explicit real-weight qualification: production JNI, never a scripted model or invented quality score. */
public final class RealOmniProbeTest {
    @Test public void recordActualNativeOutput()throws Exception{
        androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).wakeUp();
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse("Unlock the phone before qualification; locked/background timing is invalid",
                target.getSystemService(android.app.KeyguardManager.class).isDeviceLocked());
        try(ActivityScenario<ComposeHostActivity> foreground=ActivityScenario.launch(ComposeHostActivity.class)){
            foreground.onActivity(activity->activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            probe();
        }
    }
    private void probe()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bundle args=InstrumentationRegistry.getArguments();
        assertEquals("Set -e realOmni true explicitly", "true",args.getString("realOmni"));
        boolean emulator=Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone");
        assertFalse("Set -e allowEmulator true to explicitly run real weights on an emulator",
                emulator && !"true".equals(args.getString("allowEmulator")));
        java.util.List<String> abis=java.util.Arrays.asList(Build.SUPPORTED_ABIS);
        assertTrue("Expected a supported 64-bit native build",abis.contains("arm64-v8a") || abis.contains("x86_64"));
        if(emulator)assertTrue("Emulator runs require the x86_64 native library",abis.contains("x86_64"));
        assertEquals(Lfm3PocketApplication.class,context.getApplicationContext().getClass());
        File directory=new File(context.getFilesDir(),"models");
        assertTrue("Create private model directory",directory.isDirectory() || directory.mkdirs());
        File model=new File(directory,"LFM3-Omni-2B-Q4_K_M.gguf");
        File projector=new File(directory,"mmproj-LFM3-Omni-2B-Q4_K_M.gguf");
        JSONObject report=new JSONObject();report.put("device",Build.MODEL);report.put("api",Build.VERSION.SDK_INT);
        report.put("deviceClass",emulator?"EMULATOR":"ANDROID_DEVICE");
        report.put("abi",Build.SUPPORTED_ABIS[0]);
        report.put("startedUtc",java.time.Instant.now().toString());
        report.put("model",model.getName());report.put("projector",projector.getName());
        String prompt=args.getString("prompt","<|startoftext|><|im_start|>system\nYou are a concise helpful assistant. Reply in the language of the user.<|im_end|>\n<|im_start|>user\nNapisz po polsku jedno zdanie o lokalnym przetwarzaniu.<|im_end|>\n<|im_start|>assistant\n");
        String encodedPrompt=args.getString("promptBase64");
        if(encodedPrompt!=null)prompt=new String(java.util.Base64.getDecoder().decode(encodedPrompt),java.nio.charset.StandardCharsets.UTF_8);
        String media=args.getString("media","");String caseId=args.getString("caseId","text");
        int maxTokens=Integer.parseInt(args.getString("maxTokens","64"));
        assertTrue(maxTokens>=1 && maxTokens<=512);report.put("maxTokens",maxTokens);
        assertTrue(caseId.matches("[a-z0-9-]{1,40}"));
        File result=new File(context.getFilesDir(),"probe-"+caseId+".json");
        long handle=0;String error=null;StringBuilder output=new StringBuilder();
        long start=SystemClock.elapsedRealtime();final long[] first={0};
        try{
            report.put("phase","validating");writeReport(result,report);
            GgufSelection.validate(model,projector);
            report.put("validationMs",SystemClock.elapsedRealtime()-start);
            report.put("phase","loading");writeReport(result,report);
            long loadStart=SystemClock.elapsedRealtime();
            handle=NativeBridge.loadModel(model.getAbsolutePath(),projector.getAbsolutePath());
            report.put("loadMs",SystemClock.elapsedRealtime()-loadStart);
            report.put("phase","decoding");writeReport(result,report);
            if(handle==0)error="Native model/projector load returned zero";
            else if("speech".equals(caseId)){
                String speechText=args.getString("speechText","Hello from the pocket assistant.");
                String language=args.getString("speechLanguage","en");
                String voice=args.getString("speechVoice","female");
                int maxFrames=Integer.parseInt(args.getString("maxFrames","512"));
                prompt=SpeechPrompt.tts(speechText,language,voice);
                report.put("speechText",speechText);report.put("speechLanguage",language);
                report.put("speechVoice",voice);report.put("maxFrames",maxFrames);
                report.put("phase","speech");writeReport(result,report);
                ByteArrayOutputStream pcm=new ByteArrayOutputStream();
                final int[] format={0,0,0};final long[] audioStart={0};
                long speechStart=SystemClock.elapsedRealtime();NativeBridge.resetModelStop(handle);
                error=NativeBridge.generateSpeech(handle,prompt,new SpeechOptions(language,voice,maxFrames),
                        new NativeBridge.AudioSink(){
                            public void onAudioFormat(int sampleRateHz,int channelCount,int bitsPerSample){
                                format[0]=sampleRateHz;format[1]=channelCount;format[2]=bitsPerSample;
                                if(audioStart[0]==0)audioStart[0]=SystemClock.elapsedRealtime();
                            }
                            public void onAudioPcm(byte[] interleavedLittleEndianPcm){
                                if(interleavedLittleEndianPcm!=null)pcm.write(interleavedLittleEndianPcm,0,interleavedLittleEndianPcm.length);
                            }
                            public void onAudioComplete(){}
                        });
                report.put("speechMs",SystemClock.elapsedRealtime()-speechStart);
                report.put("firstAudioMs",audioStart[0]==0?JSONObject.NULL:audioStart[0]-speechStart);
                report.put("sampleRateHz",format[0]);report.put("channels",format[1]);report.put("bitsPerSample",format[2]);
                report.put("pcmBytes",pcm.size());
                if(pcm.size()>0 && format[0]>0 && format[2]==16)
                    try(FileOutputStream wav=new FileOutputStream(new File(context.getFilesDir(),"probe-"+caseId+".wav"))){
                        wav.write(SpeechWav.toWav(pcm.toByteArray(),format[0],Math.max(1,format[1]),format[2]));
                    }
                output.append("pcmBytes=").append(pcm.size());
            }else{
                long decodeStart=SystemClock.elapsedRealtime();NativeBridge.resetModelStop(handle);
                try(FileOutputStream streamed=new FileOutputStream(new File(context.getFilesDir(),"probe-"+caseId+"-stream.txt"))){
                    error=NativeBridge.generate(handle,prompt,media.isEmpty()?null:media,maxTokens,piece->{
                        if(first[0]==0)first[0]=SystemClock.elapsedRealtime();output.append(piece);
                        try{streamed.write(piece.getBytes(java.nio.charset.StandardCharsets.UTF_8));streamed.flush();}
                        catch(IOException failure){throw new java.io.UncheckedIOException(failure);}
                    });
                }
                report.put("decodeMs",SystemClock.elapsedRealtime()-decodeStart);
                report.put("firstTokenMs",first[0]==0?JSONObject.NULL:first[0]-decodeStart);
            }
        }catch(Throwable e){error=e.toString();}
        finally{if(handle!=0)NativeBridge.unloadModel(handle);}
        report.put("prompt",prompt);report.put("media",media);report.put("output",output.toString());
        report.put("error",error==null?JSONObject.NULL:error);report.put("quality","REQUIRES_REVIEW");
        report.put("phase","complete");writeReport(result,report);
        assertNull("Actual native error; inspect probe JSON",error);
        if("speech".equals(caseId)){
            assertTrue("No real speech PCM streamed",report.optInt("pcmBytes",0)>0);
            assertTrue("Speech must announce a positive sample rate",report.optInt("sampleRateHz",0)>=8000);
        }else assertTrue("No real streamed output",output.length()>0);
    }
    private static void writeReport(File result,JSONObject report)throws Exception{
        try(FileOutputStream out=new FileOutputStream(result)){out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    }
}
