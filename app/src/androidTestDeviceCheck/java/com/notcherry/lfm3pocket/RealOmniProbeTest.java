package com.notcherry.lfm3pocket;

import android.content.Context;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.core.app.ActivityScenario;
import org.json.JSONObject;
import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

/** Explicit physical-device qualification: real JNI/weights, no scripted engine or invented quality score. */
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
        assertFalse("Physical hardware required",Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"));
        assertTrue(java.util.Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a"));
        assertEquals(Lfm3PocketApplication.class,context.getApplicationContext().getClass());
        File directory=new File(context.getExternalFilesDir(null),"models");
        File model=new File(directory,"LFM3-Omni-2B-Q4_K_M.gguf");
        File projector=new File(directory,"mmproj-LFM3-Omni-2B-Q4_K_M.gguf");
        JSONObject report=new JSONObject();report.put("device",Build.MODEL);report.put("api",Build.VERSION.SDK_INT);
        report.put("startedUtc",java.time.Instant.now().toString());
        report.put("model",model.getName());report.put("projector",projector.getName());
        String prompt=args.getString("prompt","<|startoftext|><|im_start|>system\nYou are a concise helpful assistant. Reply in the language of the user.<|im_end|>\n<|im_start|>user\nNapisz po polsku jedno zdanie o lokalnym przetwarzaniu.<|im_end|>\n<|im_start|>assistant\n");
        String media=args.getString("media","");String caseId=args.getString("caseId","text");
        assertTrue(caseId.matches("[a-z0-9-]{1,40}"));
        File result=new File(context.getExternalFilesDir(null),"probe-"+caseId+".json");
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
        report.put("phase","complete");writeReport(result,report);
        assertNull("Actual native error; inspect probe JSON",error);
        assertTrue("No real streamed output",output.length()>0);
    }
    private static void writeReport(File result,JSONObject report)throws Exception{
        try(FileOutputStream out=new FileOutputStream(result)){out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    }
}
