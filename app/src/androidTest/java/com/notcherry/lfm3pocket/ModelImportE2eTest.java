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
public final class ModelImportE2eTest {
    private static final long WAIT = 12000;
    @Test public void safPickerImportsModelAndRejectsCorruptReplacement() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        TestLfm3PocketApplication app = (TestLfm3PocketApplication) context.getApplicationContext();
        app.scriptedEngine().resetForTest();
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        String pkg = context.getPackageName();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            choose(device, pkg, "valid.gguf");
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_send").enabled(true)), WAIT));
            String selected = context.getSharedPreferences("MainActivity", 0).getString("model", "");
            assertEquals(1024 * 1024, new File(selected).length());
            choose(device, pkg, "damaged.gguf");
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_status").textContains("Import failed")), WAIT));
            assertEquals(selected, context.getSharedPreferences("MainActivity", 0).getString("model", ""));
            assertTrue(new File(selected).isFile());
            File[] partials = new File(context.getFilesDir(), "models").listFiles((d,n) -> n.endsWith(".partial"));
            assertNotNull(partials); assertEquals(0, partials.length);
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_send").enabled(true)), WAIT));
        }
    }
    @Test public void metadataRejectsWrongRoleAndMismatchedPairBeforeAcceptingProperPair() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        TestLfm3PocketApplication app = (TestLfm3PocketApplication) context.getApplicationContext();
        app.scriptedEngine().resetForTest();
        context.getSharedPreferences("MainActivity",0).edit().clear().commit();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        String pkg = context.getPackageName();
        try(ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            choose(device,pkg,"projector.gguf");
            assertNotNull(device.wait(Until.findObject(By.res(pkg,"chat_status").textContains("Model error")),WAIT));
            assertEquals("",context.getSharedPreferences("MainActivity",0).getString("model",""));
            choose(device,pkg,"valid.gguf");
            assertNotNull(device.wait(Until.findObject(By.res(pkg,"chat_send").enabled(true)),WAIT));
            choose(device,pkg,"wrong-family.gguf","import_projector");
            assertNotNull(device.wait(Until.findObject(By.res(pkg,"chat_status").textContains("Cannot verify matching")),WAIT));
            assertEquals("",context.getSharedPreferences("MainActivity",0).getString("mmproj",""));
            choose(device,pkg,"projector.gguf","import_projector");
            assertNotNull(device.wait(Until.findObject(By.res(pkg,"chat_status").textContains("Model and projector loaded")),WAIT));
            assertFalse(context.getSharedPreferences("MainActivity",0).getString("mmproj","").isEmpty());
        }
    }
    private void choose(UiDevice device, String pkg, String name) throws Exception {
        choose(device,pkg,name,"import_model");
    }
    private void choose(UiDevice device, String pkg, String name,String control) throws Exception {
        new UiScrollable(new UiSelector().resourceId(pkg + ":id/model_configuration_scroll"))
                .scrollIntoView(new UiSelector().resourceId(pkg + ":id/" + control));
        UiObject2 button = device.wait(Until.findObject(By.res(pkg, control).enabled(true)), WAIT);
        assertNotNull(button); button.click();
        UiObject2 file = device.wait(Until.findObject(By.text(name)), 1500);
        if (file == null) {
            UiObject2 drawer = device.wait(Until.findObject(By.descContains("Show roots")), WAIT);
            assertNotNull("SAF storage drawer", drawer);
            device.waitForIdle();
            device.findObject(new UiSelector().descriptionContains("Show roots")).click();
            UiObject root = device.findObject(new UiSelector()
                    .resourceIdMatches(".*:id/roots_list")
                    .childSelector(new UiSelector().text("LFM test files")));
            assertTrue("SAF fixture root in storage drawer", root.waitForExists(WAIT));
            root.click();
            device.wait(Until.hasObject(By.res(java.util.regex.Pattern.compile(".*:id/dir_list"))), WAIT);
            // DocumentsUI uses a grid: the requested fixture may be below the fold on a small phone.
            new UiScrollable(new UiSelector().resourceIdMatches(".*:id/dir_list"))
                    .scrollIntoView(new UiSelector().text(name));
            file = device.wait(Until.findObject(By.text(name)), WAIT);
        }
        if (file == null) {
            java.io.ByteArrayOutputStream hierarchy = new java.io.ByteArrayOutputStream();
            device.dumpWindowHierarchy(hierarchy);
            fail("SAF document " + name + " not found. " + hierarchy.toString("UTF-8"));
        }
        device.waitForIdle();
        device.findObject(new UiSelector().text(name)).click();
    }
}
