package com.notcherry.lfm3pocket;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class PromptEncodingNativeTest {
    @Test(timeout=60000) public void nativeDecoderAcceptsUtf8SupplementaryCharactersAndEmbeddedNul() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File model=GenerationSmokeTest.copyPackagedSmokeModel(context);
        long handle=NativeBridge.loadModel(model.getAbsolutePath(),null);
        assertTrue(handle!=0);
        try {
            String prompt="Zażółć 🐈\u0000 A short story about a cat.";
            StringBuilder response=new StringBuilder();
            assertNull("Native generation must accept the standard UTF-8 payload",
                    NativeBridge.generate(handle,prompt,null,1,response::append));
            assertTrue("Native decoder must execute",response.length()>0);
        } finally { NativeBridge.unloadModel(handle); }
    }
}
