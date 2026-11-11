package com.notcherry.lfm3pocket;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class NativeSettingsTest {
    @Test public void selectedContextLimitIsEnforcedByNativeDecoder() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File model=GenerationSmokeTest.copyPackagedSmokeModel(context);
        long handle=NativeBridge.loadModel(model.getAbsolutePath(),null);assertTrue(handle!=0);
        try {
            StringBuilder prompt=new StringBuilder("Once upon a time");
            for(int i=0;i<700;i++)prompt.append(" cat");
            String tooLong=NativeBridge.generateWithSettings(handle,prompt.toString(),null,
                    new InferenceSettings(512,1,1),piece->fail("Rejected prompt emitted output"));
            assertNotNull(tooLong);assertTrue(tooLong.contains("selected context"));
            StringBuilder response=new StringBuilder();
            assertNull(NativeBridge.generateWithSettings(handle,prompt.toString(),null,
                    new InferenceSettings(2048,2,2),response::append));
            assertTrue(response.length()>0);
        } finally {NativeBridge.unloadModel(handle);}
    }
}
