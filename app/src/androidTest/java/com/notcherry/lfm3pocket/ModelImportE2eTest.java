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
    @Test public void safPickerImportsModelAndRejectsCorruptReplacement() {
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
    private void choose(UiDevice device, String pkg, String name) {
        UiObject2 button = device.wait(Until.findObject(By.res(pkg, "import_model").enabled(true)), WAIT);
        assertNotNull(button); button.click();
        UiObject2 file = device.wait(Until.findObject(By.text(name)), 1500);
        if (file == null) {
            UiObject2 drawer = device.wait(Until.findObject(By.descContains("Show roots")), WAIT);
            assertNotNull("SAF storage drawer", drawer); drawer.click();
            UiObject2 root = device.wait(Until.findObject(By.text("LFM test files")), WAIT);
            assertNotNull("SAF fixture root", root); root.click();
            file = device.wait(Until.findObject(By.text(name)), WAIT);
        }
        assertNotNull("SAF document " + name, file); file.click();
    }
}
