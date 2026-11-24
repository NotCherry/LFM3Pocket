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
public final class ModelManagerE2eTest {
    private static final long WAIT=12000;
    @Test public void modelManagerUnloadsReloadsAndRecoversFromNativeFailure() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        TestLfm3PocketApplication app=(TestLfm3PocketApplication)context.getApplicationContext();
        app.scriptedEngine().resetForTest();
        File file=new File(context.getFilesDir(),"manager-test.gguf");
        TestGgufFixtures.model(file);
        context.getSharedPreferences("MainActivity",0).edit().clear()
                .putString("model",file.getAbsolutePath()).putString("model_name","My local Omni model").commit();
        UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        String pkg=context.getPackageName();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            assertNotNull(device.wait(Until.findObject(By.res(pkg,"chat_send").enabled(true)),WAIT));
            openSettings(device,pkg);
            assertNotNull(SettingsScrollHelper.scrollToText(scenario,device,"Model: My local Omni model",true,WAIT));
            click(scenario,device,"Unload model");
            backToChat(device,pkg);
            assertNotNull(device.wait(Until.findObject(By.res(pkg,"chat_send").enabled(false)),WAIT));
            openSettings(device,pkg);
            click(scenario,device,"Load model");
            backToChat(device,pkg);
            assertNotNull(device.wait(Until.findObject(By.res(pkg,"chat_send").enabled(true)),WAIT));
            app.scriptedEngine().failNextLoad();
            openSettings(device,pkg);
            click(scenario,device,"Reload model");
            assertNotNull(device.wait(Until.findObject(By.res(pkg,"chat_status").textContains("Model error")),WAIT));
            click(scenario,device,"Retry load");
            assertNotNull(device.wait(Until.findObject(By.res(pkg,"chat_status").textContains("Text model ready")),WAIT));
            backToChat(device,pkg);
            assertNotNull(device.wait(Until.findObject(By.res(pkg,"chat_send").enabled(true)),WAIT));
        }
    }
    private void openSettings(UiDevice device,String pkg) {
        if(device.findObject(By.res(pkg,"model_configuration_scroll"))!=null) return;
        UiObject2 open=device.wait(Until.findObject(By.res(pkg,"settings_open").enabled(true)),2000);
        if(open!=null) open.click();
        assertNotNull(device.wait(Until.findObject(By.res(pkg,"model_configuration_scroll")),WAIT));
    }
    private void backToChat(UiDevice device,String pkg) {
        UiObject2 back=device.wait(Until.findObject(By.res(pkg,"settings_back").enabled(true)),WAIT);
        assertNotNull("settings_back",back);back.click();
        assertNotNull(device.wait(Until.findObject(By.res(pkg,"chat_send")),WAIT));
    }
    private void click(ActivityScenario<MainActivity> scenario,UiDevice device,String text) {
        UiObject2 button=SettingsScrollHelper.scrollToText(scenario,device,text,true,WAIT);
        assertNotNull("Model manager control: "+text,button);
        button.click();
    }
}
