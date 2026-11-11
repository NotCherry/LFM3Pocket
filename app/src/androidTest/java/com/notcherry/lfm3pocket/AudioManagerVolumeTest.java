package com.notcherry.lfm3pocket;

import android.content.Context;
import android.media.AudioManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Applies a temporary emulator music-volume change and restores the original level. */
@RunWith(AndroidJUnit4.class)
public final class AudioManagerVolumeTest {
    @Test public void productionAudioManagerAdapterAppliesPercentAndRestoresEmulatorVolume() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AudioManager platformAudio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int previous = platformAudio.getStreamVolume(AudioManager.STREAM_MUSIC);
        VolumeController controller = new VolumeController(new AndroidAudioVolume(context));
        if (platformAudio.isVolumeFixed()) {
            AndroidActionExecutor.ActionResult fixed = controller.setVolume("music", 37);
            assertFalse(fixed.succeeded);
            assertEquals("FIXED_VOLUME", fixed.status);
            assertEquals(previous, platformAudio.getStreamVolume(AudioManager.STREAM_MUSIC));
            return;
        }

        try {
            AndroidActionExecutor.ActionResult result = controller.setVolume("music", 37);
            assertTrue("AudioManager did not apply volume: " + result.status + " " + result.message,
                    result.succeeded);
            int min = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P
                    ? platformAudio.getStreamMinVolume(AudioManager.STREAM_MUSIC) : 0;
            int max = platformAudio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int expected = min + Math.round((max - min) * 0.37f);
            assertEquals(expected, platformAudio.getStreamVolume(AudioManager.STREAM_MUSIC));
            assertEquals("VOLUME_SET", result.status);
        } finally {
            platformAudio.setStreamVolume(AudioManager.STREAM_MUSIC, previous, 0);
        }
    }
}
