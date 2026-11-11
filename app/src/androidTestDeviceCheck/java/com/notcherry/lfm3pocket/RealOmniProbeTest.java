package com.notcherry.lfm3pocket;

import android.content.Context;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

/** Explicit physical-device qualification: real JNI/weights, no scripted engine or invented quality score. */
public final class RealOmniProbeTest {
    @Test public void recordActualNativeOutput()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bundle args=InstrumentationRegistry.getArguments();
        assertEquals("Set -e realOmni true explicitly", "true",args.getString("realOmni"));
        assertFalse("Physical hardware required",Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"));
        assertTrue(java.util.Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a"));
        assertEquals(Lfm3PocketApplication.class,context.getApplicationContext().getClass());
        File directory=new File(context.getExternalFilesDir(null),"models");
        File model=new File(directory,"LFM3-Omni-2B-Q4_K_M.gguf");
        File projector=new File(directory,"mmproj-LFM3-Omni-2B-Q4_K_M.gguf");
        JSONObject report=new JSONObject();report.put("device",Build.MODEL);report.put("api",Build.VERSION.SDK_INT);
        report.put("model",model.getName());report.put("projector",projector.getName());
        String prompt=args.getString("prompt","<|startoftext|><|im_start|>system\nYou are a concise helpful assistant. Reply in the language of the user.<|im_end|>\n<|im_start|>user\nNapisz po polsku jedno zdanie o lokalnym przetwarzaniu.<|im_end|>\n<|im_start|>assistant\n");
        String media=args.getString("media","");String caseId=args.getString("caseId","text");
        assertTrue(caseId.matches("[a-z0-9-]{1,40}"));
        long handle=0;String error=null;StringBuilder output=new StringBuilder();
        long start=SystemClock.elapsedRealtime();final long[] first={0};
        try{
            GgufSelection.validate(model,projector);
            handle=NativeBridge.loadModel(model.getAbsolutePath(),projector.getAbsolutePath());
            report.put("loadMs",SystemClock.elapsedRealtime()-start);
            if(handle==0)error="Native model/projector load returned zero";
            else{
                long decodeStart=SystemClock.elapsedRealtime();NativeBridge.resetModelStop(handle);
                error=NativeBridge.generate(handle,prompt,media.isEmpty()?null:media,128,piece->{
                    if(first[0]==0)first[0]=SystemClock.elapsedRealtime();output.append(piece);
                });
                report.put("decodeMs",SystemClock.elapsedRealtime()-decodeStart);
                report.put("firstTokenMs",first[0]==0?JSONObject.NULL:first[0]-decodeStart);
            }
        }catch(Throwable e){error=e.toString();}
        finally{if(handle!=0)NativeBridge.unloadModel(handle);}
        report.put("prompt",prompt);report.put("media",media);report.put("output",output.toString());
        report.put("error",error==null?JSONObject.NULL:error);report.put("quality","REQUIRES_REVIEW");
        File result=new File(context.getExternalFilesDir(null),"probe-"+caseId+".json");
        try(FileOutputStream out=new FileOutputStream(result)){out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        assertNull("Actual native error; inspect probe JSON",error);
        assertTrue("No real streamed output",output.length()>0);
    }
}
