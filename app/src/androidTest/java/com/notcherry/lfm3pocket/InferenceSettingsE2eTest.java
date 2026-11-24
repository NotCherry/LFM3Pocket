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
                verifyLimits(scenario,device,pkg,app);
                restoreDefaults(scenario,device,pkg);
                return;
            }
            openSettings(device,pkg);fields(scenario,device,pkg,"300","2","128");saveInference(device,pkg);
            assertNotNull(device.wait(Until.findObject(By.textContains("Context must be")),WAIT));
            fields(scenario,device,pkg,"2048","2","128");saveInference(device,pkg);
            assertNotNull(SettingsScrollHelper.scrollToText(
                    scenario,device,"Context 2048 • Threads 2 • Max 128 • Prompt lookup off",true,WAIT));
            if(phase.equals("prepare")) return;
            scenario.recreate();
            assertNotNull(device.wait(Until.findObject(By.res(pkg,"chat_send").enabled(true)),WAIT));
            verifyLimits(scenario,device,pkg,app);
            restoreDefaults(scenario,device,pkg);
        }
    }
    private void verifyLimits(ActivityScenario<MainActivity> scenario,UiDevice device,String pkg,TestLfm3PocketApplication app) throws Exception {
        // Summary lives on the Settings host ModelManager (U10/U15), not the chat viewport.
        openSettings(device,pkg);
        assertNotNull(SettingsScrollHelper.scrollToText(
                scenario,device,"Context 2048 • Threads 2 • Max 128 • Prompt lookup off",true,WAIT));
        assertEquals(2048,app.scriptedEngine().configuredSettings().contextTokens);
        assertEquals(2,app.scriptedEngine().configuredSettings().threads);
        assertEquals(128,app.scriptedEngine().configuredSettings().maxTokens);
    }
    private void restoreDefaults(ActivityScenario<MainActivity> scenario,UiDevice device,String pkg) throws Exception {
        openSettings(device,pkg);fields(scenario,device,pkg,"4096","4","512");saveInference(device,pkg);
        assertNotNull(SettingsScrollHelper.scrollToText(
                scenario,device,"Context 4096 • Threads 4 • Max 512 • Prompt lookup off",true,WAIT));
    }
    private void openSettings(UiDevice device,String pkg) {
        if(device.findObject(By.res(pkg,"model_configuration_scroll"))!=null) return;
        UiObject2 open=device.wait(Until.findObject(By.res(pkg,"settings_open").enabled(true)),2000);
        if(open!=null) open.click();
        assertNotNull(device.wait(Until.findObject(By.res(pkg,"model_configuration_scroll")),WAIT));
    }
    private void saveInference(UiDevice device,String pkg) throws Exception {
        // Sticky Save sits outside model_configuration_scroll.
        UiObject2 save=device.wait(Until.findObject(By.res(pkg,"settings_save").enabled(true)),WAIT);
        assertNotNull("settings_save",save);save.click();
    }
    private void fields(ActivityScenario<MainActivity> scenario,UiDevice device,String pkg,String context,String threads,String tokens) throws Exception {
        setField(scenario,device,pkg,"settings_context",R.id.settings_context,context);
        setField(scenario,device,pkg,"settings_threads",R.id.settings_threads,threads);
        setField(scenario,device,pkg,"settings_tokens",R.id.settings_tokens,tokens);
    }
    private void setField(ActivityScenario<MainActivity> scenario,UiDevice device,String pkg,String id,int viewId,String value) throws Exception {
        SettingsScrollHelper.scrollToViewId(scenario,device,viewId);
        UiObject2 field=device.wait(Until.findObject(By.res(pkg,id)),WAIT);
        assertNotNull(id,field);
        field.click();
        field.setText(value);
    }
}
