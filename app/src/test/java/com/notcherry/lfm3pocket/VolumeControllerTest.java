package com.notcherry.lfm3pocket;

import org.junit.Test;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class VolumeControllerTest {
    @Test public void mapsRequestedPercentToPermittedStreamRangeAndChecksResult() {
        FakeAudio audio = new FakeAudio();
        audio.min.put(VolumeController.Stream.MUSIC, 2);
        audio.max.put(VolumeController.Stream.MUSIC, 16);
        VolumeController controller = new VolumeController(audio);

        AndroidActionExecutor.ActionResult result = controller.setVolume("music", 50);

        assertTrue(result.succeeded);
        assertEquals("VOLUME_SET", result.status);
        assertEquals(9, audio.values.get(VolumeController.Stream.MUSIC).intValue());
        assertEquals(1, audio.setCalls);
        assertTrue(result.message.contains("50%"));
    }

    @Test public void mapsZeroAndOneHundredToTheActualStreamBounds() {
        FakeAudio audio = new FakeAudio();
        audio.min.put(VolumeController.Stream.ALARM, 1);
        audio.max.put(VolumeController.Stream.ALARM, 8);
        VolumeController controller = new VolumeController(audio);

        assertTrue(controller.setVolume("alarm", 0).succeeded);
        assertEquals(1, audio.values.get(VolumeController.Stream.ALARM).intValue());
        assertTrue(controller.setVolume("alarm", 100).succeeded);
        assertEquals(8, audio.values.get(VolumeController.Stream.ALARM).intValue());
    }

    @Test public void rejectsUnknownStreamsAndOutOfRangePercentsWithoutMutation() {
        FakeAudio audio = new FakeAudio();
        VolumeController controller = new VolumeController(audio);

        AndroidActionExecutor.ActionResult unknown = controller.setVolume("voice_call", 40);
        AndroidActionExecutor.ActionResult low = controller.setVolume("music", -1);
        AndroidActionExecutor.ActionResult high = controller.setVolume("music", 101);

        assertEquals("UNSUPPORTED_STREAM", unknown.status);
        assertEquals("INVALID_ARGUMENTS", low.status);
        assertEquals("INVALID_ARGUMENTS", high.status);
        assertEquals(0, audio.setCalls);
    }

    @Test public void fixedVolumePolicyFailsSafelyWithoutReportingSuccess() {
        FakeAudio audio = new FakeAudio();
        audio.fixed = true;

        AndroidActionExecutor.ActionResult result = new VolumeController(audio).setVolume("music", 60);

        assertFalse(result.succeeded);
        assertEquals("FIXED_VOLUME", result.status);
        assertEquals(0, audio.setCalls);
    }

    @Test public void reportsDeniedPolicyWhenDoNotDisturbBlocksAChange() {
        FakeAudio audio = new FakeAudio();
        audio.securityFailure = new SecurityException("notification policy access denied");

        AndroidActionExecutor.ActionResult result = new VolumeController(audio).setVolume("ring", 0);

        assertFalse(result.succeeded);
        assertEquals("POLICY_DENIED", result.status);
    }

    @Test public void doesNotClaimSuccessWhenThePlatformClampsTheVolume() {
        FakeAudio audio = new FakeAudio();
        audio.ignoreWrites = true;

        AndroidActionExecutor.ActionResult result = new VolumeController(audio).setVolume("music", 70);

        assertFalse(result.succeeded);
        assertEquals("POLICY_RESTRICTED", result.status);
    }

    @Test public void returnsUnavailableForAudioServiceFailures() {
        FakeAudio audio = new FakeAudio();
        audio.failure = new IllegalStateException("service disconnected");

        AndroidActionExecutor.ActionResult result = new VolumeController(audio).setVolume("music", 70);

        assertFalse(result.succeeded);
        assertEquals("AUDIO_UNAVAILABLE", result.status);
    }

    @Test public void actionExecutorAcceptsOnlyValidatedStreamAndIntegerStrings() {
        FakeAudio audio = new FakeAudio();
        VolumeActionExecutor executor = new VolumeActionExecutor(new VolumeController(audio));
        Map<String, String> extra = new HashMap<>();
        extra.put("stream", "music");
        extra.put("percent", "50");
        extra.put("on", "true");

        assertEquals("UNKNOWN_TOOL", executor.execute("toggle_flashlight", extra).status);
        assertEquals("INVALID_ARGUMENTS", executor.execute("set_volume", extra).status);
        Map<String, String> invalidPercent = new HashMap<>();
        invalidPercent.put("stream", "music");
        invalidPercent.put("percent", "50.5");
        assertEquals("INVALID_ARGUMENTS", executor.execute("set_volume", invalidPercent).status);
        assertEquals(0, audio.setCalls);
    }

    private static final class FakeAudio implements VolumeController.Audio {
        final Map<VolumeController.Stream, Integer> min = new EnumMap<>(VolumeController.Stream.class);
        final Map<VolumeController.Stream, Integer> max = new EnumMap<>(VolumeController.Stream.class);
        final Map<VolumeController.Stream, Integer> values = new EnumMap<>(VolumeController.Stream.class);
        boolean fixed;
        boolean ignoreWrites;
        int setCalls;
        RuntimeException failure;
        SecurityException securityFailure;

        FakeAudio() {
            for (VolumeController.Stream stream : VolumeController.Stream.values()) {
                min.put(stream, 0);
                max.put(stream, 10);
                values.put(stream, 3);
            }
        }

        @Override public boolean isVolumeFixed() { return fixed; }
        @Override public int minVolume(VolumeController.Stream stream) {
            if (failure != null) throw failure;
            return min.get(stream);
        }
        @Override public int maxVolume(VolumeController.Stream stream) {
            if (failure != null) throw failure;
            return max.get(stream);
        }
        @Override public int currentVolume(VolumeController.Stream stream) { return values.get(stream); }
        @Override public void setVolume(VolumeController.Stream stream, int index) {
            setCalls++;
            if (securityFailure != null) throw securityFailure;
            if (failure != null) throw failure;
            if (!ignoreWrites) values.put(stream, index);
        }
    }
}
