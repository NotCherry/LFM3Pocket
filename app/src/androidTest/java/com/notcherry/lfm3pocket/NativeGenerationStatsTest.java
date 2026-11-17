package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/** JNI contract: each generate entry clears prior stats; cancelled entry must not expose them. */
@RunWith(AndroidJUnit4.class)
public final class NativeGenerationStatsTest {
    @Test public void cancelledGenerateEntryClearsUnconsumedPriorStats() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File model = GenerationSmokeTest.copyPackagedSmokeModel(context);
        long handle = NativeBridge.loadModel(model.getAbsolutePath(), null);
        assertTrue(handle != 0L);
        try {
            NativeBridge.resetModelStop(handle);
            assertNull(NativeBridge.generate(handle, "Stats probe one.", null, 6, piece -> { }));
            long[] generationA = NativeBridge.consumeGenerationStats(handle);
            assertNotNull("Generation A should publish native stats", generationA);

            NativeBridge.resetModelStop(handle);
            assertNull(NativeBridge.generate(handle, "Stats probe two.", null, 6, piece -> { }));
            // Leave generation B stats unconsumed.

            NativeBridge.requestStop(handle);
            String blocked = NativeBridge.generate(handle, "Should not run.", null, 6, piece -> { });
            assertNull("Cancelled generation must exit before decode", blocked);
            assertNull("Cancelled entry must clear unconsumed stats from generation B",
                    NativeBridge.consumeGenerationStats(handle));
        } finally {
            NativeBridge.unloadModel(handle);
        }
    }
}