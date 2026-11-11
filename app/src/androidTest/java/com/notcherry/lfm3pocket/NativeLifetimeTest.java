package com.notcherry.lfm3pocket;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class NativeLifetimeTest {
    @Test public void unloadingDuringDecodeIsSafeAndRetiredHandlesAreRejected() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File model = GenerationSmokeTest.copyPackagedSmokeModel(context);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            for (int attempt=0; attempt<3; attempt++) {
                long handle = NativeBridge.loadModel(model.getAbsolutePath(), null);
                assertTrue(handle != 0);
                try {
                    NativeBridge.resetModelStop(handle);
                    CountDownLatch streaming = new CountDownLatch(1);
                    Future<String> generation = worker.submit(() -> NativeBridge.generate(handle,
                            "Once upon a time", null, 512, piece -> streaming.countDown()));
                    assertTrue(streaming.await(30, TimeUnit.SECONDS));
                    NativeBridge.unloadModel(handle);
                    assertNull(generation.get(30, TimeUnit.SECONDS));
                    NativeBridge.unloadModel(handle); // Idempotent, cannot free twice.
                    assertNotNull(NativeBridge.generate(handle, "A story", null, 1, piece -> fail("Retired handle emitted")));
                } finally { NativeBridge.unloadModel(handle); }
            }
        } finally { worker.shutdownNow(); }
    }
    @Test public void stoppingOneModelCannotCancelAnotherSession() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File model = GenerationSmokeTest.copyPackagedSmokeModel(context);
        long first = NativeBridge.loadModel(model.getAbsolutePath(), null);
        long second = NativeBridge.loadModel(model.getAbsolutePath(), null);
        assertTrue(first != 0 && second != 0);
        try {
            NativeBridge.requestStop(first);
            StringBuilder text = new StringBuilder();
            assertNull(NativeBridge.generate(second, "Once upon a time", null, 2, text::append));
            assertTrue("Other session must still produce tokens", text.length() > 0);
            assertNull(NativeBridge.generate(first, "A story", null, 2, piece -> fail("Cancelled model emitted")));
            NativeBridge.resetModelStop(first);
            text.setLength(0);
            assertNull(NativeBridge.generate(first, "Once upon a time", null, 2, text::append));
            assertTrue(text.length() > 0);
        } finally { NativeBridge.unloadModel(first); NativeBridge.unloadModel(second); }
    }
}
