package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Device smoke test for optional Cactus Whistle STT integration.
 *
 * <p>Verifies provider selection/persistence and safe failure handling without requiring
 * cloud/network access. A full transcription pass requires a build with the Cactus runtime
 * and a local whistle.cact model configured on-device; when unavailable this test asserts
 * that the provider reports unavailability/errors safely and that the default Omni path
 * remains selectable.
 */
@RunWith(AndroidJUnit4.class)
public class CactusWhistleSttSmokeTest {
    @After public void resetPrefs() {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        RuntimePreferences prefs = new RuntimePreferences(app);
        prefs.setSpeechInputMode(SpeechInputMode.OMNI_AUDIO);
        prefs.setCactusWhistleModelPath("");
    }

    @Test public void defaultsToOmniAndPersistsWhistleSelection() {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        RuntimePreferences prefs = new RuntimePreferences(app);
        prefs.setSpeechInputMode(SpeechInputMode.OMNI_AUDIO);
        assertEquals(SpeechInputMode.OMNI_AUDIO, prefs.speechInputMode());
        prefs.setSpeechInputMode(SpeechInputMode.CACTUS_WHISTLE_STT);
        assertEquals(SpeechInputMode.CACTUS_WHISTLE_STT, new RuntimePreferences(app).speechInputMode());
        assertEquals(SpeechInputMode.CACTUS_WHISTLE_STT, SpeechInputProviders.current(app).mode());
    }

    @Test public void unavailableWhistleFailsSafelyWithoutLlmAction() {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        RuntimePreferences prefs = new RuntimePreferences(app);
        prefs.setSpeechInputMode(SpeechInputMode.CACTUS_WHISTLE_STT);
        prefs.setCactusWhistleModelPath("");
        SttProvider provider = SpeechInputProviders.current(app);
        assertEquals(SpeechInputMode.CACTUS_WHISTLE_STT, provider.mode());
        SttAvailability availability = provider.availability(app);
        assertFalse("Whistle should be unavailable without runtime/model configured", availability.available);
        SttResult result = provider.transcribe(app, new File(app.getCacheDir(), "missing.wav"), new AtomicBoolean(false));
        assertNotNull(result);
        assertTrue(result.status == SttResult.Status.UNAVAILABLE || result.status == SttResult.Status.ERROR);
    }

    @Test public void switchingBackToOmniRestoresDefaultProvider() {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        RuntimePreferences prefs = new RuntimePreferences(app);
        prefs.setSpeechInputMode(SpeechInputMode.CACTUS_WHISTLE_STT);
        prefs.setSpeechInputMode(SpeechInputMode.OMNI_AUDIO);
        assertEquals(SpeechInputMode.OMNI_AUDIO, SpeechInputProviders.current(app).mode());
        assertTrue(SpeechInputProviders.current(app) instanceof OmniSpeechInputProvider);
    }
}
