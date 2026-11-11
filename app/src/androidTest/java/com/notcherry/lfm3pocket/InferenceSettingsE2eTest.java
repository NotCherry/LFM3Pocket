package com.notcherry.lfm3pocket;

import android.content.Context;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class InferenceSettingsE2eTest {
    private static final long WAIT=12000;
    @Test public void invalidSettingsAreRejectedAndSavedLimitsSurviveRecreation() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        TestLfm3PocketApplication app=(TestLfm3PocketApplication)context.getApplicationContext();
        String phase=InstrumentationRegistry.getArguments().getString("settingsPhase", "");
        if(!phase.equals("verify")) {
            File model=new File(context.getFilesDir(),"settings-test.gguf");TestGgufFixtures.model(model);
            context.getSharedPreferences("MainActivity",0).edit().clear().putString("model",model.getAbsolutePath()).commit();
        }
        UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        String pkg=context.getPackageName();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            assertNotNull(device.wait(Until.findObject(By.res(pkg,"chat_send").enabled(true)),WAIT));
            if(phase.equals("verify")) {
                verifyLimits(device,app);
                restoreDefaults(device,pkg);
                return;
            }
            open(device);fields(device,pkg,"300","2","128");click(device,"Save");
            assertNotNull(device.wait(Until.findObject(By.textContains("Context must be")),WAIT));
            fields(device,pkg,"2048","2","128");click(device,"Save");
            assertNotNull(device.wait(Until.findObject(By.text("Context 2048 • Threads 2 • Max 128")),WAIT));
            if(phase.equals("prepare")) return;
            scenario.recreate();
            assertNotNull(device.wait(Until.findObject(By.res(pkg,"chat_send").enabled(true)),WAIT));
            verifyLimits(device,app);
            restoreDefaults(device,pkg);
        }
    }
    private void verifyLimits(UiDevice device,TestLfm3PocketApplication app) {
        assertNotNull(device.wait(Until.findObject(By.text("Context 2048 • Threads 2 • Max 128")),WAIT));
        assertEquals(2048,app.scriptedEngine().configuredSettings().contextTokens);
        assertEquals(2,app.scriptedEngine().configuredSettings().threads);
        assertEquals(128,app.scriptedEngine().configuredSettings().maxTokens);
    }
    private void restoreDefaults(UiDevice device,String pkg) {
        open(device);fields(device,pkg,"4096","4","512");click(device,"Save");
        assertNotNull(device.wait(Until.findObject(By.text("Context 4096 • Threads 4 • Max 512")),WAIT));
    }
    private void open(UiDevice device){click(device,"Settings");}
    private void click(UiDevice device,String text){UiObject2 button=device.wait(Until.findObject(text.equals("Save") ? By.res("android","button1").enabled(true) : By.text(text).enabled(true)),WAIT);assertNotNull(text,button);button.click();}
    private void fields(UiDevice device,String pkg,String context,String threads,String tokens){
        device.wait(Until.findObject(By.res(pkg,"settings_context")),WAIT).setText(context);
        device.findObject(By.res(pkg,"settings_threads")).setText(threads);
        device.findObject(By.res(pkg,"settings_tokens")).setText(tokens);
    }
}
